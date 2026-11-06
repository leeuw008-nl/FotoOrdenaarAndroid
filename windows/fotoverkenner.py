from __future__ import annotations

import json
import os
import re
import queue
import threading
import subprocess
import sys
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

from PIL import Image, ImageTk, ImageOps, ExifTags

try:
    import cv2
except Exception:
    cv2 = None

try:
    import tkintermapview
except Exception:
    tkintermapview = None

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".webp", ".gif"}
VIDEO_EXTS = {".mp4", ".mov", ".avi", ".mkv", ".m4v", ".wmv", ".3gp", ".mts", ".m2ts"}
MEDIA_EXTS = IMAGE_EXTS | VIDEO_EXTS
GPS_NAMES = ExifTags.GPSTAGS


def num(value):
    try:
        return float(value)
    except Exception:
        try:
            return float(value.numerator) / float(value.denominator)
        except Exception:
            return 0.0


def gps_ref(value):
    if isinstance(value, bytes):
        try:
            value = value.decode("ascii", "ignore")
        except Exception:
            pass
    return str(value).strip().upper()


def dms(value, ref):
    if not value or len(value) < 3:
        return None
    number = num(value[0]) + num(value[1]) / 60 + num(value[2]) / 3600
    return -number if gps_ref(ref) in ("S", "W") else number


@dataclass
class Media:
    path: Path
    modified: datetime
    capture: datetime | None = None
    lat: float | None = None
    lon: float | None = None
    place: str = ""
    width: int | None = None
    height: int | None = None
    camera: str = ""
    size: int = 0
    duration: float | None = None
    fps: float | None = None
    codec: str = ""

    @property
    def is_video(self):
        return self.path.suffix.lower() in VIDEO_EXTS

    @property
    def date(self):
        return self.capture or self.modified


def inspect_file(path):
    stat = path.stat()
    media = Media(path, datetime.fromtimestamp(stat.st_mtime), size=stat.st_size)
    if path.suffix.lower() in IMAGE_EXTS:
        try:
            with Image.open(path) as image:
                media.width, media.height = image.size
                exif = image.getexif()
                raw_date = exif.get(36867) or exif.get(306)
                if raw_date:
                    try:
                        media.capture = datetime.strptime(str(raw_date), "%Y:%m:%d %H:%M:%S")
                    except ValueError:
                        pass
                media.camera = " ".join(
                    str(value).strip() for value in (exif.get(271), exif.get(272)) if value
                )
                try:
                    gps_ifd = exif.get_ifd(34853)
                except Exception:
                    gps_ifd = {}
                gps = {GPS_NAMES.get(key, key): value for key, value in gps_ifd.items()}
                media.lat = dms(gps.get("GPSLatitude"), gps.get("GPSLatitudeRef", "N"))
                media.lon = dms(gps.get("GPSLongitude"), gps.get("GPSLongitudeRef", "E"))
        except Exception:
            pass
    elif path.suffix.lower() in VIDEO_EXTS and cv2 is not None:
        try:
            capture = cv2.VideoCapture(str(path))
            if capture.isOpened():
                media.width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH)) or None
                media.height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT)) or None
                fps = float(capture.get(cv2.CAP_PROP_FPS) or 0)
                frames = float(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
                media.fps = fps if fps > 0 else None
                media.duration = frames / fps if fps > 0 and frames > 0 else None
                try:
                    code = int(capture.get(cv2.CAP_PROP_FOURCC))
                    media.codec = "".join(chr((code >> (8 * i)) & 0xFF) for i in range(4)).strip("\x00 ")
                except Exception:
                    pass
            capture.release()
        except Exception:
            pass
    return media


def video_frame(path, size=(1000, 700)):
    if cv2 is None:
        return None
    capture = None
    try:
        capture = cv2.VideoCapture(str(path))
        if not capture.isOpened():
            return None
        capture.set(cv2.CAP_PROP_POS_MSEC, 1000)
        ok, frame = capture.read()
        if not ok:
            capture.set(cv2.CAP_PROP_POS_MSEC, 0)
            ok, frame = capture.read()
        if not ok:
            return None
        frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        image = Image.fromarray(frame)
        image.thumbnail(size, Image.Resampling.LANCZOS)
        return image.copy()
    except Exception:
        return None
    finally:
        if capture is not None:
            capture.release()


class App:
    def __init__(self, root):
        self.root = root
        root.title("FotoVerkenner")
        root.geometry("1440x900")
        root.minsize(1000, 650)

        self.folder: Path | None = None
        self.active_folder: Path | None = None
        self.items: list[Media] = []
        self.filtered: list[Media] = []
        self.selected: Media | None = None
        self.sort = tk.StringVar(value="Bestandsdatum")
        self.query = tk.StringVar()
        self.year = tk.StringVar()
        self.month = tk.StringVar()
        self.day = tk.StringVar()
        self.status = tk.StringVar(value="Kies een fotomap om foto's en video's te bekijken.")
        self.preview_photo = None
        self.map_widget = None
        self.map_marker = None
        self.map_markers = []
        self.map_content_available = False
        self.album_map_path = None
        self.album_map_enabled = tk.BooleanVar(value=True)
        self.active_tab = "Kaart"
        self.tab_buttons = {}
        self.tab_frames = {}
        self.highlighting_media_album = False
        self.tree_paths: dict[str, Path] = {}
        self.scan_queue = queue.Queue()
        self.gallery_thumb_queue = queue.Queue()
        self.gallery_generation = 0
        self.gallery_thumb_cache = {}
        self.gallery_thumb_labels = {}
        self.scan_generation = 0
        self.scan_running = False
        self.expand_queue = []
        self.render_generation = 0
        self.build_ui()

    def build_ui(self):
        top = ttk.Frame(self.root, padding=(10, 8))
        top.pack(fill="x")
        ttk.Button(top, text="📁 Fotomap kiezen", command=self.choose).pack(side="left")
        self.folder_label = ttk.Label(top, text="Geen map geselecteerd")
        self.folder_label.pack(side="left", padx=12)
        ttk.Label(top, text="Sorteren:").pack(side="left", padx=(18, 4))
        sort_box = ttk.Combobox(
            top, textvariable=self.sort, state="readonly", width=19,
            values=["Bestandsdatum", "Opnamedatum", "Gemeente-plaats", "Naam"]
        )
        sort_box.pack(side="left")
        sort_box.bind("<<ComboboxSelected>>", lambda _event: self.filter())
        ttk.Label(top, text="Zoeken:").pack(side="left", padx=(14, 4))
        search = ttk.Entry(top, textvariable=self.query, width=24)
        search.pack(side="left")
        search.bind("<KeyRelease>", lambda _event: self.filter())
        ttk.Button(top, text="Vernieuwen", command=self.scan).pack(side="right")

        self.breadcrumb_bar = ttk.Frame(self.root, padding=(12, 0, 12, 7))
        self.breadcrumb_bar.pack(fill="x")
        self.update_breadcrumb()

        filters = ttk.Frame(self.root, padding=(10, 0, 10, 8))
        filters.pack(fill="x")
        ttk.Label(filters, text="Datumfilter  Jaar").pack(side="left")
        ttk.Entry(filters, textvariable=self.year, width=7).pack(side="left", padx=4)
        ttk.Label(filters, text="Maand").pack(side="left")
        ttk.Entry(filters, textvariable=self.month, width=5).pack(side="left", padx=4)
        ttk.Label(filters, text="Dag").pack(side="left")
        ttk.Entry(filters, textvariable=self.day, width=5).pack(side="left", padx=4)
        ttk.Button(filters, text="Toepassen", command=self.filter).pack(side="left", padx=8)
        ttk.Button(filters, text="Wissen", command=self.clear_dates).pack(side="left")

        panes = ttk.Panedwindow(self.root, orient="horizontal")
        panes.pack(fill="both", expand=True, padx=10, pady=(0, 8))
        left = ttk.Frame(panes)
        right = ttk.Frame(panes, padding=(8, 0, 0, 0))
        panes.add(left, weight=5)
        panes.add(right, weight=6)

        # Albums en bestanden blijven samen in hetzelfde hoofdvenster,
        # naast elkaar zoals de gecombineerde navigatie in Android.
        left_split = ttk.Panedwindow(left, orient="horizontal")
        left_split.pack(fill="both", expand=True)
        albums = ttk.Frame(left_split)
        media_list = ttk.Frame(left_split)
        left_split.add(albums, weight=2)
        left_split.add(media_list, weight=5)

        album_bar = ttk.Frame(albums)
        album_bar.pack(fill="x", pady=(0, 4))
        ttk.Label(album_bar, text="📁 Albums", font=("Segoe UI", 10, "bold")).pack(side="left")
        self.expand_button = ttk.Button(album_bar, text="Alles uitklappen", command=self.toggle_all)
        self.expand_button.pack(side="right")
        self.album_tree = ttk.Treeview(albums, show="tree", selectmode="browse")
        album_scroll = ttk.Scrollbar(albums, orient="vertical", command=self.album_tree.yview)
        self.album_tree.configure(yscrollcommand=album_scroll.set)
        self.album_tree.pack(side="left", fill="both", expand=True)
        album_scroll.pack(side="right", fill="y")
        self.album_tree.bind("<<TreeviewSelect>>", self.album_selected)
        self.album_tree.bind("<<TreeviewOpen>>", self.album_opened)

        media_bar = ttk.Frame(media_list)
        media_bar.pack(fill="x", pady=(5, 4))
        ttk.Label(media_bar, text="🖼 Foto's en video's", font=("Segoe UI", 10, "bold")).pack(side="left")
        columns = ("name", "kind", "date", "size", "place")
        self.table = ttk.Treeview(media_list, columns=columns, show="headings", selectmode="browse")
        for column, title, width in [
            ("name", "Bestand", 250), ("kind", "Type", 65), ("date", "Datum", 140),
            ("size", "Grootte", 75), ("place", "GPS / plaats", 165)
        ]:
            self.table.heading(column, text=title, command=lambda col=column: self.sort_column(col))
            self.table.column(column, width=width, anchor="w")
        y_scroll = ttk.Scrollbar(media_list, orient="vertical", command=self.table.yview)
        x_scroll = ttk.Scrollbar(media_list, orient="horizontal", command=self.table.xview)
        self.table.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        self.table.pack(side="left", fill="both", expand=True)
        y_scroll.pack(side="right", fill="y")
        x_scroll.pack(side="bottom", fill="x")
        self.table.bind("<<TreeviewSelect>>", self.select)
        self.table.bind("<Double-1>", self.open_file)

        tab_bar = tk.Frame(right, bg="#e5e7eb")
        tab_bar.pack(fill="x")
        tab_body = ttk.Frame(right)
        tab_body.pack(fill="both", expand=True)
        image_tab = ttk.Frame(tab_body)
        map_tab = ttk.Frame(tab_body)
        metadata_tab = ttk.Frame(tab_body)
        gallery_tab = ttk.Frame(tab_body)
        delete_tab = ttk.Frame(tab_body)
        for panel in (image_tab, map_tab, metadata_tab, gallery_tab, delete_tab):
            panel.place(relx=0, rely=0, relwidth=1, relheight=1)
        self.tab_frames = {
            "Medium": image_tab, "Kaart": map_tab, "Metadata": metadata_tab, "Gallery": gallery_tab, "Verwijderen": delete_tab
        }
        for name, label in (("Gallery", "▦  Gallery"), ("Medium", "🖼  Medium"), ("Kaart", "📍  Kaart"), ("Metadata", "ℹ  Metadata"), ("Verwijderen", "🗑  Verwijderen")):
            button = tk.Button(
                tab_bar, text=label, font=("Segoe UI", 12, "bold"),
                padx=18, pady=9, relief="flat", bd=0,
                command=lambda target=name: self.show_tab(target)
            )
            if name == "Verwijderen":
                button.pack(side="right", padx=(3, 0), pady=(0, 2))
            else:
                button.pack(side="left", padx=(0, 3), pady=(0, 2))
            self.tab_buttons[name] = button
        self.show_tab("Medium")

        self.preview = ttk.Label(image_tab, text="Selecteer een foto of video", anchor="center")
        self.preview.pack(fill="both", expand=True, padx=6, pady=6)
        self.preview.bind("<Configure>", self.resize_preview)
        image_actions = ttk.Frame(image_tab)
        image_actions.pack(fill="x", padx=6, pady=(0, 6))
        ttk.Button(image_actions, text="Openen / afspelen", command=self.open_file).pack(side="left")
        self.delete_button = ttk.Button(image_actions, text="🗑 Verwijderen", command=self.delete_selected_media, state="disabled")
        self.delete_button.pack(side="left", padx=(8, 0))

        gallery_top = ttk.Frame(gallery_tab, padding=(8, 8, 8, 4))
        gallery_top.pack(fill="x")
        self.gallery_heading = ttk.Label(gallery_top, text="Gallery", font=("Segoe UI", 11, "bold"))
        self.gallery_heading.pack(side="left")
        self.gallery_count = ttk.Label(gallery_top, text="0 items")
        self.gallery_count.pack(side="right")
        gallery_container = ttk.Frame(gallery_tab)
        gallery_container.pack(fill="both", expand=True, padx=6, pady=(0, 6))
        self.gallery_canvas = tk.Canvas(gallery_container, highlightthickness=0)
        gallery_scroll = ttk.Scrollbar(gallery_container, orient="vertical", command=self.gallery_canvas.yview)
        self.gallery_canvas.configure(yscrollcommand=gallery_scroll.set)
        self.gallery_canvas.pack(side="left", fill="both", expand=True)
        gallery_scroll.pack(side="right", fill="y")
        self.gallery_inner = ttk.Frame(self.gallery_canvas)
        self.gallery_window = self.gallery_canvas.create_window((0, 0), window=self.gallery_inner, anchor="nw")
        self.gallery_inner.bind("<Configure>", lambda _e: self.gallery_canvas.configure(scrollregion=self.gallery_canvas.bbox("all")))
        self.gallery_canvas.bind("<Configure>", self.gallery_resize)

        delete_top = ttk.Frame(delete_tab, padding=(8, 8, 8, 4))
        delete_top.pack(fill="x")
        self.delete_heading = ttk.Label(delete_top, text="Te verwijderen media", font=("Segoe UI", 11, "bold"))
        self.delete_heading.pack(side="left")
        self.delete_count = ttk.Label(delete_top, text="0 kandidaten")
        self.delete_count.pack(side="right")
        ttk.Label(delete_tab, text="Het geselecteerde bestand staat vast bovenaan. Vink de overige gevonden bestanden aan die ook verwijderd mogen worden.", wraplength=900).pack(anchor="w", padx=10, pady=(0, 6))
        delete_container = ttk.Frame(delete_tab)
        delete_container.pack(fill="both", expand=True, padx=6, pady=(0, 6))
        self.delete_canvas = tk.Canvas(delete_container, highlightthickness=0)
        delete_scroll = ttk.Scrollbar(delete_container, orient="vertical", command=self.delete_canvas.yview)
        self.delete_canvas.configure(yscrollcommand=delete_scroll.set)
        self.delete_canvas.pack(side="left", fill="both", expand=True)
        delete_scroll.pack(side="right", fill="y")
        self.delete_inner = ttk.Frame(self.delete_canvas)
        self.delete_window = self.delete_canvas.create_window((0, 0), window=self.delete_inner, anchor="nw")
        self.delete_inner.bind("<Configure>", lambda _e: self.delete_canvas.configure(scrollregion=self.delete_canvas.bbox("all")))
        self.delete_canvas.bind("<Configure>", lambda e: self.delete_canvas.itemconfigure(self.delete_window, width=e.width))
        # Muiswiel scrollt ook wanneer de cursor boven een kandidaat-tegel staat.
        self.root.bind_all("<MouseWheel>", self._scroll_delete_candidates, add="+")
        self.root.bind_all("<Button-4>", self._scroll_delete_candidates, add="+")
        self.root.bind_all("<Button-5>", self._scroll_delete_candidates, add="+")
        delete_actions = ttk.Frame(delete_tab, padding=(8, 4, 8, 8))
        delete_actions.pack(fill="x")
        ttk.Button(delete_actions, text="Annuleren", command=lambda: self.show_tab("Gallery")).pack(side="right", padx=(6, 0))
        self.delete_confirm_button = ttk.Button(delete_actions, text="Geselecteerde bestanden verwijderen", command=self.confirm_delete_candidates, state="disabled")
        self.delete_confirm_button.pack(side="right")

        metadata_heading = ttk.Label(
            metadata_tab, text="Metadata van geselecteerd bestand",
            font=("Segoe UI", 11, "bold")
        )
        metadata_heading.pack(anchor="w", padx=10, pady=(10, 4))
        self.details = tk.Text(metadata_tab, wrap="word", state="disabled", font=("Segoe UI", 10))
        self.details.pack(fill="both", expand=True, padx=8, pady=(0, 8))

        if tkintermapview is not None:
            self.map_widget = tkintermapview.TkinterMapView(map_tab, corner_radius=0)
            # Gebruik CARTO-kaarttegels in plaats van de standaard OSM-tegelserver,
            # die op sommige netwerken een Access blocked-melding geeft.
            # OSM-Duitsland levert openbare rastertegels zonder API-sleutel.
            # De CARTO-endpoint gaf bij sommige installaties "API key required".
            self.map_widget.set_tile_server(
                "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
                max_zoom=19
            )
            self.map_widget.pack(fill="both", expand=True, padx=6, pady=6)
            self.map_widget.set_position(52.519142, 6.423672)
            self.map_widget.set_zoom(7)
        else:
            ttk.Label(
                map_tab,
                text="De kaartmodule is niet beschikbaar. Gebruik 'Open kaart extern'.",
                anchor="center"
            ).pack(fill="both", expand=True)
        map_actions = ttk.Frame(map_tab)
        map_actions.pack(fill="x", padx=6, pady=(0, 6))
        ttk.Label(map_actions, text="Kaarttegels: OpenStreetMap Deutschland · © OpenStreetMap").pack(side="right", padx=8)
        ttk.Button(map_actions, text="Open in Google Maps", command=self.open_external_map).pack(side="left")
        ttk.Button(map_actions, text="Locatie zoeken", command=self.lookup).pack(side="left", padx=6)
        ttk.Checkbutton(
            map_actions, text="Album automatisch op kaart",
            variable=self.album_map_enabled
        ).pack(side="left", padx=(12, 4))
        ttk.Button(map_actions, text="Toon albumspelden", command=self.show_album_on_map).pack(side="left", padx=4)

        ttk.Label(
            self.root, textvariable=self.status, relief="sunken", anchor="w", padding=5
        ).pack(fill="x", side="bottom")

    def choose(self):
        selected = filedialog.askdirectory(title="Kies de map met foto's en video's")
        if selected:
            self.folder = Path(selected)
            self.active_folder = self.folder
            self.folder_label.configure(text=str(self.folder))
            self.scan()

    def clear_detail_panels(self):
        """Wis oude inhoud voordat een nieuwe selectie wordt verwerkt."""
        self.selected = None
        if hasattr(self, "delete_button"):
            self.delete_button.configure(state="disabled")
        self.preview_photo = None
        if hasattr(self, "preview"):
            self.preview.configure(image="", text="Selecteer een foto of video")
        if hasattr(self, "details"):
            self.details.configure(state="normal")
            self.details.delete("1.0", "end")
            self.details.configure(state="disabled")
        if self.map_widget:
            self.clear_map_markers()
            self.map_widget.set_position(52.519142, 6.423672)
            self.map_widget.set_zoom(7)
        self.map_content_available = False
        self.update_tab_colors()

    def scan(self):
        if not self.folder or not self.folder.exists():
            return
        self.scan_generation += 1
        generation = self.scan_generation
        root_folder = self.folder
        self.scan_running = True
        self.items = []
        self.filtered = []
        self.table.delete(*self.table.get_children())
        self.clear_detail_panels()
        self.status.set("Bestanden inventariseren…")
        self.build_album_tree()

        def worker():
            found = []
            checked = 0
            for current, _dirs, files in os.walk(root_folder, followlinks=False):
                for filename in files:
                    path = Path(current) / filename
                    if path.suffix.lower() not in MEDIA_EXTS:
                        continue
                    checked += 1
                    try:
                        stat = path.stat()
                        found.append(Media(path, datetime.fromtimestamp(stat.st_mtime), size=stat.st_size))
                    except (OSError, PermissionError):
                        pass
                    if checked % 250 == 0:
                        self.scan_queue.put((generation, "inventory_progress", checked))
            self.scan_queue.put((generation, "inventory_done", found))

        threading.Thread(target=worker, name="FotoVerkennerInventaris", daemon=True).start()
        self.root.after(100, lambda: self.poll_scan(generation))

    def start_metadata_scan(self, generation):
        paths = [item.path for item in self.items]
        def worker():
            batch = []
            checked = 0
            for path in paths:
                try:
                    batch.append(inspect_file(path))
                except Exception:
                    continue
                checked += 1
                if len(batch) >= 30:
                    self.scan_queue.put((generation, "metadata_batch", batch))
                    batch = []
                if checked % 100 == 0:
                    self.scan_queue.put((generation, "metadata_progress", checked))
            if batch:
                self.scan_queue.put((generation, "metadata_batch", batch))
            self.scan_queue.put((generation, "metadata_done", checked))
        threading.Thread(target=worker, name="FotoVerkennerMetadata", daemon=True).start()

    def poll_scan(self, generation):
        if generation != self.scan_generation:
            return
        finished = False
        while True:
            try:
                item_generation, kind, payload = self.scan_queue.get_nowait()
            except queue.Empty:
                break
            if item_generation != generation:
                continue
            if kind == "inventory_progress":
                self.status.set(f"Bestanden inventariseren… {payload} gevonden")
            elif kind == "inventory_done":
                self.items = payload
                self.scan_running = False
                self.filter()
                self.status.set(f"{len(self.items)} bestanden getoond; metadata wordt nu opgehaald…")
                self.update_tab_colors()
                self.start_metadata_scan(generation)
            elif kind == "metadata_batch":
                path_indexes = {item.path: index for index, item in enumerate(self.items)}
                for enriched in payload:
                    index = path_indexes.get(enriched.path)
                    if index is not None:
                        self.items[index] = enriched
            elif kind == "metadata_progress":
                self.status.set(f"Metadata lezen… {payload} bestanden verwerkt")
            elif kind == "metadata_done":
                self.filter()
                self.status.set(f"Gereed: {len(self.items)} bestanden; metadata verwerkt voor {payload} bestanden.")
                if self.album_map_path:
                    self.show_album_on_map(self.album_map_path, switch_tab=False)
                self.update_tab_colors()
                finished = True
        if not finished:
            self.root.after(100, lambda: self.poll_scan(generation))

    def build_album_tree(self):
        self.album_tree.delete(*self.album_tree.get_children())
        self.tree_paths.clear()
        if not self.folder:
            return
        root_id = str(self.folder)
        self.tree_paths[root_id] = self.folder
        self.album_tree.insert("", "end", iid=root_id, text=f"📁 {self.folder.name}", open=True)
        self.populate_album_node(root_id)
        self.album_tree.selection_set(root_id)
        self.album_tree.focus(root_id)
        self.expand_button.configure(text="Alles uitklappen")

    def add_album_placeholder(self, node_id, path):
        try:
            with os.scandir(path) as entries:
                has_subfolder = any(entry.is_dir(follow_symlinks=False) for entry in entries)
            if has_subfolder:
                placeholder = "__lazy__" + node_id
                self.album_tree.insert(node_id, "end", iid=placeholder, text="…")
        except (OSError, PermissionError):
            pass

    def populate_album_node(self, node_id):
        path = self.tree_paths.get(node_id)
        if path is None:
            return
        children = self.album_tree.get_children(node_id)
        if children and all(child.startswith("__lazy__") for child in children):
            self.album_tree.delete(*children)
        elif children:
            return
        try:
            directories = []
            with os.scandir(path) as entries:
                for entry in entries:
                    try:
                        if entry.is_dir(follow_symlinks=False):
                            directories.append(Path(entry.path))
                    except OSError:
                        continue
            directories.sort(key=lambda p: p.name.lower())
            for directory in directories:
                directory_id = str(directory)
                if directory_id in self.tree_paths:
                    continue
                self.tree_paths[directory_id] = directory
                self.album_tree.insert(node_id, "end", iid=directory_id, text=f"📁 {directory.name}")
                # Placeholder voorkomt dat onderliggende mappen vooraf worden doorzocht.
                self.album_tree.insert(directory_id, "end", iid="__lazy__" + directory_id, text="…")
        except (OSError, PermissionError):
            return

    def toggle_all(self):
        if not self.folder:
            return
        expand = self.expand_button.cget("text") == "Alles uitklappen"
        if not expand:
            stack = list(self.album_tree.get_children(""))
            while stack:
                node = stack.pop()
                stack.extend(self.album_tree.get_children(node))
                self.album_tree.item(node, open=False)
            self.expand_button.configure(text="Alles uitklappen")
            return
        self.expand_queue = list(self.album_tree.get_children(""))
        self.expand_button.configure(text="Bezig met uitklappen…")
        self.root.after(1, self.expand_all_step)

    def expand_all_step(self):
        if not self.expand_queue:
            self.expand_button.configure(text="Alles inklappen")
            return
        node = self.expand_queue.pop(0)
        self.populate_album_node(node)
        self.album_tree.item(node, open=True)
        self.expand_queue.extend(
            child for child in self.album_tree.get_children(node)
            if not child.startswith("__lazy__")
        )
        self.root.after(1, self.expand_all_step)

    def album_opened(self, _event=None):
        selection = self.album_tree.focus()
        if selection:
            self.populate_album_node(selection)

    def album_selected(self, _event=None):
        if self.highlighting_media_album:
            return
        selection = self.album_tree.selection()
        if not selection:
            return
        path = self.tree_paths.get(selection[0])
        if path and path.is_dir():
            self.clear_detail_panels()
            self.set_media_tabs_visible(False)
            self.album_map_path = path
            self.active_folder = path
            self.update_breadcrumb()
            self.filter()
            if self.album_map_enabled.get():
                self.album_map_path = path
                self.show_album_on_map(path, switch_tab=False)
            else:
                self.album_map_path = None
            self.show_tab("Gallery")

    def update_breadcrumb(self):
        if not hasattr(self, "breadcrumb_bar"):
            return
        for child in self.breadcrumb_bar.winfo_children():
            child.destroy()
        if not self.folder:
            ttk.Label(self.breadcrumb_bar, text="Kruimelpad: nog geen map gekozen").pack(side="left")
            return
        current = self.active_folder or self.folder
        ttk.Label(self.breadcrumb_bar, text="Locatie:").pack(side="left", padx=(0, 5))
        try:
            relative = current.relative_to(self.folder)
            chain = [self.folder] + [self.folder / part for part in relative.parts]
        except ValueError:
            chain = [current]
        for index, path in enumerate(chain):
            if index:
                ttk.Label(self.breadcrumb_bar, text="›").pack(side="left", padx=3)
            ttk.Button(
                self.breadcrumb_bar, text=f"📁 {path.name}",
                command=lambda target=path: self.navigate_to(target)
            ).pack(side="left")

    def navigate_to(self, path):
        if not path.is_dir():
            return
        self.active_folder = path
        if self.folder:
            try:
                relative = path.relative_to(self.folder)
                current_id = str(self.folder)
                self.populate_album_node(current_id)
                self.album_tree.item(current_id, open=True)
                for part in relative.parts:
                    current_id = str(Path(current_id) / part)
                    if current_id not in self.tree_paths:
                        break
                    self.populate_album_node(current_id)
                    self.album_tree.item(current_id, open=True)
                if current_id in self.tree_paths:
                    self.album_tree.selection_set(current_id)
                    self.album_tree.focus(current_id)
                    self.album_tree.see(current_id)
            except ValueError:
                pass
        self.update_breadcrumb()
        self.filter()

    def clear_dates(self):
        self.year.set("")
        self.month.set("")
        self.day.set("")
        self.filter()

    def filter(self):
        if not hasattr(self, "table"):
            return
        query = self.query.get().strip().lower()
        result = []
        try:
            year = int(self.year.get()) if self.year.get().strip() else None
            month = int(self.month.get()) if self.month.get().strip() else None
            day = int(self.day.get()) if self.day.get().strip() else None
        except ValueError:
            messagebox.showwarning("Datumfilter", "Gebruik cijfers voor jaar, maand en dag.")
            return
        active = self.active_folder or self.folder
        for media in self.items:
            if active and active not in media.path.parents and media.path.parent != active:
                continue
            if query and query not in media.path.name.lower() and query not in str(media.path.parent).lower() and query not in media.place.lower():
                continue
            date = media.date
            if year and date.year != year:
                continue
            if month and date.month != month:
                continue
            if day and date.day != day:
                continue
            result.append(media)
        mode = self.sort.get()
        if mode == "Opnamedatum":
            result.sort(key=lambda item: item.capture or datetime.min, reverse=True)
        elif mode == "Gemeente-plaats":
            result.sort(key=lambda item: (item.place.lower(), item.path.name.lower()))
        elif mode == "Naam":
            result.sort(key=lambda item: item.path.name.lower())
        else:
            result.sort(key=lambda item: item.modified, reverse=True)
        self.filtered = result
        self.render_gallery()
        self.render_generation += 1
        generation = self.render_generation
        self.table.delete(*self.table.get_children())
        self.render_offset = 0
        self.render_filter_batch(generation, active)

    def render_filter_batch(self, generation, active):
        if generation != self.render_generation:
            return
        start = getattr(self, "render_offset", 0)
        end = min(start + 250, len(self.filtered))
        for index in range(start, end):
            media = self.filtered[index]
            date_text = media.date.strftime("%d-%m-%Y %H:%M:%S")
            size = f"{media.size / (1024 * 1024):.1f} MB" if media.size >= 1048576 else f"{media.size / 1024:.0f} KB"
            gps = f"{media.lat:.5f}, {media.lon:.5f}" if media.lat is not None and media.lon is not None else media.place
            icon = "📄" if media.is_video else "🖼"
            self.table.insert(
                "", "end", iid=str(index),
                values=(f"{icon} {media.path.name}", "Video" if media.is_video else "Foto", date_text, size, gps)
            )
        self.render_offset = end
        if end < len(self.filtered):
            self.status.set(f"Weergave opbouwen… {end} van {len(self.filtered)} bestanden")
            self.root.after(1, lambda: self.render_filter_batch(generation, active))
        else:
            self.status.set(f"{len(self.filtered)} van {len(self.items)} bestanden weergegeven in {active or 'de fotomap'}.")

    def sort_column(self, column):
        self.sort.set({
            "date": "Opnamedatum", "name": "Naam", "place": "Gemeente-plaats"
        }.get(column, "Bestandsdatum"))
        self.filter()

    def select(self, _event=None):
        selection = self.table.selection()
        if not selection:
            return
        try:
            media = self.filtered[int(selection[0])]
        except (ValueError, IndexError):
            return
        self.clear_detail_panels()
        self.set_media_tabs_visible(True)
        self.album_map_path = None
        self.selected = media
        self.delete_button.configure(state="normal")
        self.highlight_media_album(media)
        lines = [
            f"Bestand: {media.path.name}",
            f"Volledig pad: {media.path}",
            f"Type: {'Video' if media.is_video else 'Foto'}",
            f"Bestandsdatum: {media.modified:%d-%m-%Y %H:%M:%S}",
            f"Opnamedatum: {media.capture:%d-%m-%Y %H:%M:%S}" if media.capture else "Opnamedatum: niet beschikbaar",
            f"Grootte: {media.size:,} bytes"
        ]
        if media.width and media.height:
            lines.append(f"Afmetingen: {media.width} × {media.height}")
        if media.camera:
            lines.append(f"Camera: {media.camera}")
        if media.lat is not None and media.lon is not None:
            lines.append(f"GPS: {media.lat:.6f}, {media.lon:.6f}")
        if media.place:
            lines.append(f"Plaats: {media.place}")
        if media.is_video:
            if media.duration is not None:
                seconds = int(media.duration)
                lines.append(f"Duur: {seconds // 60:02d}:{seconds % 60:02d}")
            if media.fps is not None:
                lines.append(f"Beeldsnelheid: {media.fps:.2f} fps")
            if media.codec:
                lines.append(f"Videocodec: {media.codec}")
        self.details.configure(state="normal")
        self.details.delete("1.0", "end")
        self.details.insert("1.0", "\n".join(lines))
        self.details.configure(state="disabled")
        self.render_preview()
        self.update_map()
        self.update_tab_colors()
        self.show_tab("Medium")

    def highlight_media_album(self, media):
        """Markeer de map van het gekozen bestand zonder de huidige bestandenlijst te filteren."""
        if not self.folder:
            return
        try:
            relative = media.path.parent.relative_to(self.folder)
        except ValueError:
            return
        node_id = str(self.folder)
        if node_id not in self.tree_paths:
            return
        self.populate_album_node(node_id)
        self.album_tree.item(node_id, open=True)
        for part in relative.parts:
            next_id = str(Path(node_id) / part)
            if next_id not in self.tree_paths:
                break
            self.populate_album_node(next_id)
            self.album_tree.item(node_id, open=True)
            node_id = next_id
        if node_id in self.tree_paths:
            self.highlighting_media_album = True
            self.album_tree.selection_set(node_id)
            self.album_tree.focus(node_id)
            self.album_tree.see(node_id)
            self.root.after(200, lambda: setattr(self, "highlighting_media_album", False))

    def resize_preview(self, _event=None):
        if self.selected:
            self.render_preview()

    def gallery_resize(self, event=None):
        if event is not None:
            self.gallery_canvas.itemconfigure(self.gallery_window, width=event.width)
        self.root.after_idle(self.layout_gallery)

    def layout_gallery(self):
        if not hasattr(self, "gallery_inner"):
            return
        columns = max(2, min(6, self.gallery_canvas.winfo_width() // 180))
        for index, child in enumerate(self.gallery_inner.winfo_children()):
            row, column = divmod(index, columns)
            child.grid_configure(row=row, column=column, sticky="nsew", padx=4, pady=4)
        for column in range(columns):
            self.gallery_inner.grid_columnconfigure(column, weight=1, uniform="gallery")

    def render_gallery(self):
        if not hasattr(self, "gallery_inner"):
            return
        self.gallery_generation += 1
        generation = self.gallery_generation
        self.gallery_thumb_labels = {}
        for child in self.gallery_inner.winfo_children():
            child.destroy()
        media_items = list(self.filtered)
        self.gallery_count.configure(text=str(len(media_items)) + " items")
        folder = self.active_folder or self.folder
        self.gallery_heading.configure(text="Gallery - " + (folder.name if folder else "geen album geselecteerd"))
        if not media_items:
            ttk.Label(self.gallery_inner, text="Geen media in deze map of onderliggende mappen.").grid(row=0, column=0, sticky="w", padx=12, pady=12)
            self.update_tab_colors()
            return
        for index, media in enumerate(media_items):
            tile = ttk.Frame(self.gallery_inner, relief="ridge", padding=4)
            tile.grid(row=index // 4, column=index % 4, sticky="nsew", padx=4, pady=4)
            tile.columnconfigure(0, weight=1)
            thumb = tk.Label(tile, text="VIDEO" if media.is_video else "Laden...", anchor="center", width=20, height=7, bg="#e5e7eb", fg="#374151")
            thumb.grid(row=0, column=0, sticky="nsew")
            thumb.bind("<Button-1>", lambda _e, item=media: self.select_gallery_item(item))
            name = ttk.Label(tile, text=media.path.name, wraplength=160, justify="center")
            name.grid(row=1, column=0, sticky="ew", pady=(3, 0))
            name.bind("<Button-1>", lambda _e, item=media: self.select_gallery_item(item))
            self.gallery_thumb_labels[media.path] = thumb
            cached = self.gallery_thumb_cache.get(media.path)
            if cached is not None:
                photo = ImageTk.PhotoImage(cached)
                thumb.configure(image=photo, text="", width=160, height=110)
                thumb.image = photo
            else:
                threading.Thread(target=self.load_gallery_thumbnail, args=(generation, media.path), name="GalleryThumb-" + str(generation), daemon=True).start()
        self.layout_gallery()
        self.root.after(100, self.poll_gallery_thumbnails)

    def load_gallery_thumbnail(self, generation, path):
        if path.suffix.lower() in VIDEO_EXTS:
            thumbnail = video_frame(path, (220, 150))
        else:
            try:
                with Image.open(path) as source:
                    image = ImageOps.exif_transpose(source)
                    image.thumbnail((220, 150), Image.Resampling.LANCZOS)
                    thumbnail = image.copy()
            except Exception:
                thumbnail = None
        self.gallery_thumb_queue.put((generation, path, thumbnail))

    def poll_gallery_thumbnails(self):
        generation_now = self.gallery_generation
        while True:
            try:
                generation, path, thumbnail = self.gallery_thumb_queue.get_nowait()
            except queue.Empty:
                break
            if generation != generation_now or thumbnail is None:
                continue
            self.gallery_thumb_cache[path] = thumbnail
            label = self.gallery_thumb_labels.get(path)
            if label is not None and label.winfo_exists():
                try:
                    photo = ImageTk.PhotoImage(thumbnail)
                    label.configure(image=photo, text="", width=160, height=110)
                    label.image = photo
                except Exception:
                    pass
        if any(t.is_alive() for t in threading.enumerate() if t.name.startswith("GalleryThumb-")):
            self.root.after(100, self.poll_gallery_thumbnails)

    def select_gallery_item(self, media):
        try:
            index = self.filtered.index(media)
        except ValueError:
            return
        self.table.selection_set(str(index))
        self.table.focus(str(index))
        self.table.see(str(index))
        self.select()
        self.show_tab("Medium")

    def render_preview(self):

        media = self.selected
        if not media:
            return
        try:
            width = max(200, self.preview.winfo_width() - 20)
            height = max(180, self.preview.winfo_height() - 24)
            if media.is_video:
                image = video_frame(media.path, (width, height))
                if image is None:
                    self.preview.configure(image="", text="Videovoorbeeld niet beschikbaar\nDubbelklik of kies Openen / afspelen")
                    self.preview_photo = None
                    return
            else:
                with Image.open(media.path) as source:
                    image = ImageOps.exif_transpose(source)
                    image.thumbnail((width, height), Image.Resampling.LANCZOS)
                    image = image.copy()
            self.preview_photo = ImageTk.PhotoImage(image)
            self.preview.configure(image=self.preview_photo, text="")
        except Exception:
            self.preview.configure(image="", text="Voorbeeld niet beschikbaar")
            self.preview_photo = None

    def clear_map_markers(self):
        if self.map_marker:
            try:
                self.map_marker.delete()
            except Exception:
                pass
        self.map_marker = None
        for marker in self.map_markers:
            try:
                marker.delete()
            except Exception:
                pass
        self.map_markers = []

    def update_map(self):
        if not self.map_widget:
            return
        media = self.selected
        self.clear_map_markers()
        self.map_content_available = bool(media and media.lat is not None and media.lon is not None)
        if self.map_content_available:
            self.map_widget.set_position(media.lat, media.lon)
            self.map_widget.set_zoom(16)
            try:
                self.map_marker = self.map_widget.set_marker(media.lat, media.lon, text="", command=lambda marker, item=media: self.open_map_media(item))
            except Exception:
                self.map_marker = None
        else:
            self.map_widget.set_position(52.519142, 6.423672)
            self.map_widget.set_zoom(7)
        self.update_tab_colors()

    def show_album_on_map(self, path=None, switch_tab=True):
        if not self.map_widget:
            messagebox.showwarning("Kaart", "De kaartmodule is niet beschikbaar.")
            return
        album = path or self.active_folder or self.folder
        if not album:
            return
        # Path.parents bevat ook alle dieper geneste subalbums.
        points = [
            item for item in self.items
            if album in item.path.parents and item.lat is not None and item.lon is not None
        ]
        self.clear_map_markers()
        self.map_content_available = bool(points)
        if not points:
            self.map_widget.set_position(52.519142, 6.423672)
            self.map_widget.set_zoom(7)
            if self.scan_running:
                self.status.set(f"Bestanden in {album.name} worden nog geïnventariseerd…")
            else:
                self.status.set(f"Nog geen GPS-metadata beschikbaar in album {album.name}.")
        else:
            latitudes = [item.lat for item in points]
            longitudes = [item.lon for item in points]
            self.map_widget.set_position(
                sum(latitudes) / len(latitudes), sum(longitudes) / len(longitudes)
            )
            self.map_widget.set_zoom(16 if len(points) == 1 else 12)
            for item in points:
                try:
                    self.map_markers.append(
                        self.map_widget.set_marker(item.lat, item.lon, text="", command=lambda marker, media=item: self.open_map_media(media))
                    )
                except Exception:
                    continue
            self.status.set(
                f"{len(self.map_markers)} GPS-spelden getoond voor album {album.name} inclusief subalbums."
            )
        if switch_tab:
            self.show_tab("Kaart")
        self.update_tab_colors()

    def _scroll_delete_candidates(self, event):
        """Scroll kandidaten met het muiswiel, ook boven tegels en selectievakjes."""
        if self.active_tab != "Verwijderen":
            return
        try:
            widget = self.root.winfo_containing(event.x_root, event.y_root)
            inside = False
            while widget is not None:
                if widget is self.delete_inner or widget is self.delete_canvas:
                    inside = True
                    break
                widget = widget.master
            if not inside:
                return
            if getattr(event, "num", None) == 4:
                self.delete_canvas.yview_scroll(-1, "units")
            elif getattr(event, "num", None) == 5:
                self.delete_canvas.yview_scroll(1, "units")
            else:
                delta = int(getattr(event, "delta", 0))
                if delta:
                    self.delete_canvas.yview_scroll(-1 if delta > 0 else 1, "units")
            return "break"
        except Exception:
            return

    def show_tab(self, name):
        if name not in self.tab_frames:
            return
        self.active_tab = name
        self.tab_frames[name].tkraise()
        self.update_tab_colors()

    def set_media_tabs_visible(self, visible):
        """Toon Medium en Metadata alleen wanneer een afzonderlijk medium is geselecteerd."""
        medium = self.tab_buttons.get("Medium")
        metadata = self.tab_buttons.get("Metadata")
        if not medium or not metadata:
            return
        if visible:
            if not medium.winfo_manager():
                medium.pack(side="left", padx=(0, 3), pady=(0, 2), before=self.tab_buttons["Kaart"])
            if not metadata.winfo_manager():
                metadata.pack(side="left", padx=(0, 3), pady=(0, 2))
        else:
            medium.pack_forget()
            metadata.pack_forget()
            if self.active_tab in ("Medium", "Metadata"):
                self.show_tab("Kaart")

    @staticmethod
    def normalized_media_stem(path):
        """Verwijder herkenbare versie-aanduidingen uit een bestandsnaam."""
        stem = path.stem.lower().strip()
        patterns = (
            r"(?:[_ -](?:bewerkt|bewerking|edited|edit|copy|kopie|kopie\d+|small|klein|thumb|thumbnail|resized|resize|scaled|compressed|compressie|export|web))+$",
            r"(?:[ _-]\(\d+\))+$",
            r"(?:[-_]\d+)$",
        )
        previous = None
        while stem != previous:
            previous = stem
            for pattern in patterns:
                stem = re.sub(pattern, "", stem, flags=re.IGNORECASE)
        return stem

    @staticmethod
    def filename_suffix_key(path):
        """Neem exact de laatste zes tekens van de bestandsnaam, zonder extensie."""
        stem = Path(path).stem
        return stem[-6:].casefold() if len(stem) >= 6 else ""

    def find_derived_media(self, selected):
        """Zoek foto's waarvan de laatste zes bestandsnaamtekens exact gelijk zijn."""
        album = self.active_folder or self.folder
        if not album:
            return []
        suffix = self.filename_suffix_key(selected.path)
        if not suffix:
            return []
        matches = []
        for candidate in self.items:
            if candidate.path == selected.path or candidate.is_video:
                continue
            if album not in candidate.path.parents:
                continue
            candidate_suffix = self.filename_suffix_key(candidate.path)
            if candidate_suffix == suffix:
                matches.append((
                    candidate,
                    f"Laatste zes tekens gelijk: {suffix}",
                    False
                ))
        return matches

    def delete_selected_media(self):
        selected = self.selected
        if not selected:
            messagebox.showinfo("Verwijderen", "Selecteer eerst één foto of video.")
            return
        if not selected.path.exists():
            messagebox.showwarning("Verwijderen", "Het geselecteerde bestand bestaat niet meer.")
            return

        self.delete_selected_item = selected
        self.delete_candidate_choices = []
        for child in self.delete_inner.winfo_children():
            child.destroy()

        # Toon eerst direct de gekozen foto in het tabblad Verwijderen.
        self.delete_heading.configure(text="Geselecteerde foto: " + selected.path.name)
        self.delete_count.configure(text="Foto wordt getoond…")
        self.delete_confirm_button.configure(state="disabled")
        self._add_delete_candidate_tile(
            selected, "Geselecteerde bestand — wordt verwijderd", mandatory=True
        )
        self.show_tab("Verwijderen")
        self.update_idletasks()

        # Start de snelle bestandsnaamzoekactie pas nadat het tabblad is getoond.
        self.after(50, lambda item=selected: self._load_derived_candidates(item))

    def _load_derived_candidates(self, selected):
        if self.delete_selected_item is not selected:
            return
        self.delete_count.configure(text="Zoeken op laatste zes tekens…")
        matches = self.find_derived_media(selected)
        if self.delete_selected_item is not selected:
            return
        self.delete_heading.configure(text="Verwijderkandidaten voor " + selected.path.name)
        self.delete_count.configure(text=f"{len(matches) + 1} bestanden")
        self.delete_confirm_button.configure(state="normal")
        for candidate, reason, _strong_name in matches:
            variable = tk.BooleanVar(value=False)
            self._add_delete_candidate_tile(candidate, reason, variable=variable)
            self.delete_candidate_choices.append((candidate, variable))
        if not matches:
            ttk.Label(self.delete_inner, text="Geen andere foto's met dezelfde laatste zes tekens gevonden.").grid(
                row=1, column=0, sticky="w", padx=12, pady=12
            )
        self.delete_canvas.configure(scrollregion=self.delete_canvas.bbox("all"))
        self.show_tab("Verwijderen")

    def _add_delete_candidate_tile(self, media, reason, variable=None, mandatory=False):
        index = len(self.delete_inner.winfo_children())
        tile = ttk.Frame(self.delete_inner, relief="ridge", padding=7)
        tile.grid(row=index // 3, column=index % 3, sticky="nsew", padx=5, pady=5)
        self.delete_inner.columnconfigure(index % 3, weight=1)
        thumb = tk.Label(tile, text="VIDEO" if media.is_video else "Voorbeeld niet beschikbaar",
                         width=22, height=7, bg="#e5e7eb", fg="#374151", anchor="center")
        thumb.pack(fill="x")
        try:
            cached = self.gallery_thumb_cache.get(media.path)
            if cached is None:
                if media.is_video:
                    cached = video_frame(media.path, (220, 150))
                else:
                    with Image.open(media.path) as source:
                        preview = ImageOps.exif_transpose(source)
                        preview.thumbnail((220, 150), Image.Resampling.LANCZOS)
                        cached = preview.copy()
            if cached is not None:
                photo = ImageTk.PhotoImage(cached)
                thumb.configure(image=photo, text="", width=220, height=150)
                thumb.image = photo
        except Exception:
            pass
        # Een readonly invoerveld maakt de volledige bestandsnaam selecteerbaar en kopieerbaar.
        filename_field = tk.Entry(
            tile, relief="flat", justify="center", state="readonly",
            readonlybackground=tile.cget("background"),
            font=("Segoe UI", 9)
        )
        filename_field.pack(fill="x", pady=(5, 2))
        filename_field.configure(state="normal")
        filename_field.insert(0, media.path.name)
        filename_field.configure(state="readonly")
        filename_field.bind("<Control-a>", lambda event, field=filename_field: (field.selection_range(0, "end"), "break")[1])
        ttk.Label(tile, text=str(media.path.parent), wraplength=220, foreground="#555555").pack(fill="x")
        ttk.Label(tile, text=reason, wraplength=220, foreground="#555555").pack(fill="x", pady=(3, 4))
        if mandatory:
            ttk.Label(tile, text="☑ Wordt verwijderd", foreground="#9b1c1c",
                      font=("Segoe UI", 9, "bold")).pack(anchor="w")
        else:
            ttk.Checkbutton(tile, text="Ook verwijderen", variable=variable).pack(anchor="w")

    def confirm_delete_candidates(self):
        selected = getattr(self, "delete_selected_item", None)
        if not selected or not selected.path.exists():
            messagebox.showwarning("Verwijderen", "Het oorspronkelijke bestand bestaat niet meer.")
            return
        extra = [media for media, variable in self.delete_candidate_choices if variable.get()]
        paths = [selected.path] + [media.path for media in extra]
        if not messagebox.askyesno(
            "Definitief bevestigen",
            "De volgende bestanden worden definitief verwijderd:\\n\\n"
            + "\\n".join(str(path) for path in paths)
            + "\\n\\nWeet je zeker dat je wilt doorgaan?"
        ):
            return
        failures = []
        deleted = set()
        for path in paths:
            try:
                path.unlink()
                deleted.add(path)
            except OSError as error:
                failures.append(f"{path}: {error}")
        if deleted:
            self.items = [item for item in self.items if item.path not in deleted]
            self.filtered = [item for item in self.filtered if item.path not in deleted]
            self.clear_detail_panels()
            self.filter()
            self.show_tab("Gallery")
            self.status.set(f"{len(deleted)} bestand(en) verwijderd.")
        if failures:
            messagebox.showwarning("Niet alles verwijderd", "\\n".join(failures), parent=self.root)
        elif deleted:
            messagebox.showinfo("Verwijderen", f"{len(deleted)} bestand(en) verwijderd.", parent=self.root)

    def open_map_media(self, media):
        """Open het medium waarop de gebruiker op de kaartspeld klikt."""
        self.set_media_tabs_visible(True)
        self.select_gallery_item(media)

    def update_tab_colors(self):
        if not self.tab_buttons:
            return
        media = self.selected
        available = {
            "Medium": media is not None,
            "Kaart": bool(getattr(self, "map_content_available", False)),
            "Metadata": media is not None and any((
                media.capture is not None, media.lat is not None, media.lon is not None,
                bool(media.camera), media.width is not None, media.height is not None,
                media.is_video
            )),
            "Gallery": bool(self.filtered),
            "Verwijderen": bool(getattr(self, "delete_selected_item", None))
        }
        for name, button in self.tab_buttons.items():
            if available[name] and name == self.active_tab:
                bg, fg = "#16803c", "#ffffff"
            elif available[name]:
                bg, fg = "#b7e4c7", "#14532d"
            else:
                bg, fg = "#e5e7eb", "#4b5563"
            button.configure(
                bg=bg, fg=fg, activebackground="#86c99d",
                activeforeground="#123b22", highlightthickness=0
            )

    def map(self):
        self.show_tab("Kaart")
        self.update_map()

    def open_external_map(self):
        media = self.selected
        if media and media.lat is not None and media.lon is not None:
            url = f"https://www.google.com/maps?q={media.lat},{media.lon}"
            webbrowser.open(url)
        elif media:
            messagebox.showinfo("Kaart", "Voor dit bestand zijn geen GPS-coördinaten gevonden.")

    def open_file(self, _event=None):
        media = self.selected
        if not media:
            return
        try:
            if sys.platform == "win32":
                os.startfile(str(media.path))
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(media.path)])
            else:
                subprocess.Popen(["xdg-open", str(media.path)])
        except Exception as error:
            messagebox.showerror("Openen mislukt", str(error))

    def lookup(self):
        media = self.selected
        if not media or media.lat is None or media.lon is None:
            messagebox.showinfo("Locatie zoeken", "Voor dit bestand zijn geen GPS-coördinaten gevonden.")
            return
        try:
            params = urllib.parse.urlencode({
                "lat": media.lat, "lon": media.lon, "format": "jsonv2",
                "zoom": 16, "addressdetails": 1
            })
            request = urllib.request.Request(
                "https://nominatim.openstreetmap.org/reverse?" + params,
                headers={"User-Agent": "FotoVerkennerWindows/1.0"}
            )
            with urllib.request.urlopen(request, timeout=12) as response:
                data = json.loads(response.read().decode("utf-8"))
            address = data.get("address", {})
            town = address.get("city") or address.get("town") or address.get("village") or address.get("municipality") or ""
            road = address.get("road") or address.get("neighbourhood") or address.get("suburb") or ""
            region = address.get("county") or address.get("state") or ""
            media.place = ", ".join(value for value in (town, road, region) if value)
            self.filter()
            try:
                index = self.filtered.index(media)
                self.table.selection_set(str(index))
                self.table.see(str(index))
                self.select()
            except ValueError:
                pass
            self.status.set("Locatie opgehaald via OpenStreetMap.")
        except Exception as error:
            messagebox.showwarning(
                "Locatie zoeken",
                f"Plaatsnaam niet opgehaald. GPS blijft beschikbaar.\n\n{error}"
            )


def main():
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", 1.15)
    except Exception:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
