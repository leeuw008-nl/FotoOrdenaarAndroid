from __future__ import annotations

import json
import os
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
    return media


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
        self.active_tab = "Afbeelding"
        self.tab_buttons = {}
        self.tab_frames = {}
        self.highlighting_media_album = False
        self.tree_paths: dict[str, Path] = {}
        self.scan_queue = queue.Queue()
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

        ttk.Label(media_list, text="🖼 Foto's en video's", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(5, 4))
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
        for panel in (image_tab, map_tab, metadata_tab):
            panel.place(relx=0, rely=0, relwidth=1, relheight=1)
        self.tab_frames = {
            "Afbeelding": image_tab, "Kaart": map_tab, "Metadata": metadata_tab
        }
        for name, label in (("Afbeelding", "🖼  Afbeelding"), ("Kaart", "📍  Kaart"), ("Metadata", "ℹ  Metadata")):
            button = tk.Button(
                tab_bar, text=label, font=("Segoe UI", 12, "bold"),
                padx=18, pady=9, relief="flat", bd=0,
                command=lambda target=name: self.show_tab(target)
            )
            button.pack(side="left", padx=(0, 3), pady=(0, 2))
            self.tab_buttons[name] = button
        self.show_tab("Afbeelding")

        self.preview = ttk.Label(image_tab, text="Selecteer een foto of video", anchor="center")
        self.preview.pack(fill="both", expand=True, padx=6, pady=6)
        self.preview.bind("<Configure>", self.resize_preview)
        image_actions = ttk.Frame(image_tab)
        image_actions.pack(fill="x", padx=6, pady=(0, 6))
        ttk.Button(image_actions, text="Openen / afspelen", command=self.open_file).pack(side="left")

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
            self.album_map_path = path
            self.active_folder = path
            self.update_breadcrumb()
            self.filter()
            if self.album_map_enabled.get():
                self.album_map_path = path
                self.show_album_on_map(path, switch_tab=True)
            else:
                self.album_map_path = None

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
        self.album_map_path = None
        self.selected = media
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
        self.details.configure(state="normal")
        self.details.delete("1.0", "end")
        self.details.insert("1.0", "\n".join(lines))
        self.details.configure(state="disabled")
        self.render_preview()
        self.update_map()
        self.update_tab_colors()

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
        if self.selected and not self.selected.is_video:
            self.render_preview()

    def render_preview(self):
        media = self.selected
        if not media:
            return
        if media.is_video:
            self.preview.configure(image="", text="VIDEO\nDubbelklik of kies Openen / afspelen")
            self.preview_photo = None
            return
        try:
            width = max(200, self.preview.winfo_width() - 20)
            height = max(180, self.preview.winfo_height() - 24)
            with Image.open(media.path) as image:
                image = ImageOps.exif_transpose(image)
                image.thumbnail((width, height), Image.Resampling.LANCZOS)
                self.preview_photo = ImageTk.PhotoImage(image.copy())
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
                self.map_marker = self.map_widget.set_marker(media.lat, media.lon, text=media.path.name)
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
                        self.map_widget.set_marker(item.lat, item.lon, text=item.path.name)
                    )
                except Exception:
                    continue
            self.status.set(
                f"{len(self.map_markers)} GPS-spelden getoond voor album {album.name} inclusief subalbums."
            )
        if switch_tab:
            self.show_tab("Kaart")
        self.update_tab_colors()

    def show_tab(self, name):
        if name not in self.tab_frames:
            return
        self.active_tab = name
        self.tab_frames[name].tkraise()
        self.update_tab_colors()

    def update_tab_colors(self):
        if not self.tab_buttons:
            return
        media = self.selected
        available = {
            "Afbeelding": media is not None,
            "Kaart": bool(getattr(self, "map_content_available", False)),
            "Metadata": media is not None and any((
                media.capture is not None, media.lat is not None, media.lon is not None,
                bool(media.camera), media.width is not None, media.height is not None,
                media.is_video
            ))
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
