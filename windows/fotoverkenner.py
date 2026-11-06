from __future__ import annotations
import json, os, subprocess, sys, urllib.parse, urllib.request, webbrowser
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from PIL import Image, ImageTk, ExifTags

IMAGE_EXTS={".jpg",".jpeg",".png",".tif",".tiff",".bmp",".webp",".gif"}
VIDEO_EXTS={".mp4",".mov",".avi",".mkv",".m4v",".wmv",".3gp",".mts",".m2ts"}
MEDIA_EXTS=IMAGE_EXTS|VIDEO_EXTS
GPS_NAMES=ExifTags.GPSTAGS

def num(v):
    try:return float(v)
    except Exception:
        try:return float(v.numerator)/float(v.denominator)
        except Exception:return 0.0
def gps_ref(v):
    if isinstance(v,bytes):
        try:v=v.decode("ascii","ignore")
        except Exception:pass
    return str(v).strip().upper()

def dms(v,ref):
    if not v or len(v)<3:return None
    n=num(v[0])+num(v[1])/60+num(v[2])/3600
    return -n if gps_ref(ref) in ("S","W") else n

@dataclass
class Media:
    path:Path
    modified:datetime
    capture:datetime|None=None
    lat:float|None=None
    lon:float|None=None
    place:str=""
    width:int|None=None
    height:int|None=None
    camera:str=""
    size:int=0
    @property
    def is_video(self):return self.path.suffix.lower() in VIDEO_EXTS
    @property
    def date(self):return self.capture or self.modified

def inspect_file(path):
    st=path.stat(); m=Media(path,datetime.fromtimestamp(st.st_mtime),size=st.st_size)
    if path.suffix.lower() in IMAGE_EXTS:
        try:
            with Image.open(path) as im:
                m.width,m.height=im.size; ex=im.getexif()
                raw=ex.get(36867) or ex.get(306)
                if raw:
                    try:m.capture=datetime.strptime(str(raw),"%Y:%m:%d %H:%M:%S")
                    except ValueError:pass
                m.camera=" ".join(str(x).strip() for x in (ex.get(271),ex.get(272)) if x)
                try:
                    gps_ifd=ex.get_ifd(34853)
                except Exception:
                    gps_ifd={}
                gps={GPS_NAMES.get(k,k):v for k,v in gps_ifd.items()}
                m.lat=dms(gps.get("GPSLatitude"),gps.get("GPSLatitudeRef","N"))
                m.lon=dms(gps.get("GPSLongitude"),gps.get("GPSLongitudeRef","E"))
        except Exception:pass
    return m

class App:
    def __init__(self,root):
        self.root=root;root.title("FotoVerkenner");root.geometry("1280x820");root.minsize(900,600)
        self.folder=None;self.items=[];self.filtered=[];self.selected=None
        self.sort=tk.StringVar(value="Bestandsdatum");self.query=tk.StringVar()
        self.year=tk.StringVar();self.month=tk.StringVar();self.day=tk.StringVar()
        self.status=tk.StringVar(value="Kies een fotomap om foto's en video's te bekijken.")
        self.ui()
    def ui(self):
        top=ttk.Frame(self.root,padding=10);top.pack(fill="x")
        ttk.Button(top,text="Fotomap kiezen",command=self.choose).pack(side="left")
        self.folder_label=ttk.Label(top,text="Geen map geselecteerd");self.folder_label.pack(side="left",padx=12)
        ttk.Label(top,text="Sorteren:").pack(side="left",padx=(18,4))
        cb=ttk.Combobox(top,textvariable=self.sort,state="readonly",width=19,values=["Bestandsdatum","Opnamedatum","Gemeente-plaats","Naam"])
        cb.pack(side="left");cb.bind("<<ComboboxSelected>>",lambda e:self.filter())
        ttk.Label(top,text="Zoeken:").pack(side="left",padx=(14,4))
        ent=ttk.Entry(top,textvariable=self.query,width=24);ent.pack(side="left");ent.bind("<KeyRelease>",lambda e:self.filter())
        ttk.Button(top,text="Vernieuwen",command=self.scan).pack(side="right")
        bar=ttk.Frame(self.root,padding=(10,0,10,8));bar.pack(fill="x")
        ttk.Label(bar,text="Datumfilter  Jaar").pack(side="left");ttk.Entry(bar,textvariable=self.year,width=7).pack(side="left",padx=4)
        ttk.Label(bar,text="Maand").pack(side="left");ttk.Entry(bar,textvariable=self.month,width=5).pack(side="left",padx=4)
        ttk.Label(bar,text="Dag").pack(side="left");ttk.Entry(bar,textvariable=self.day,width=5).pack(side="left",padx=4)
        ttk.Button(bar,text="Toepassen",command=self.filter).pack(side="left",padx=8);ttk.Button(bar,text="Wissen",command=self.clear_dates).pack(side="left")
        panes=ttk.Panedwindow(self.root,orient="horizontal");panes.pack(fill="both",expand=True,padx=10,pady=(0,8))
        left=ttk.Frame(panes);right=ttk.Frame(panes,padding=10);panes.add(left,weight=4);panes.add(right,weight=2)
        cols=("name","kind","date","size","place");self.table=ttk.Treeview(left,columns=cols,show="headings",selectmode="browse")
        for c,t,w in [("name","Bestand",280),("kind","Type",75),("date","Datum",145),("size","Grootte",85),("place","GPS / plaats",190)]:
            self.table.heading(c,text=t,command=lambda col=c:self.sort_column(col));self.table.column(c,width=w,anchor="w")
        ys=ttk.Scrollbar(left,orient="vertical",command=self.table.yview);xs=ttk.Scrollbar(left,orient="horizontal",command=self.table.xview)
        self.table.configure(yscrollcommand=ys.set,xscrollcommand=xs.set);self.table.grid(row=0,column=0,sticky="nsew");ys.grid(row=0,column=1,sticky="ns");xs.grid(row=1,column=0,sticky="ew")
        left.rowconfigure(0,weight=1);left.columnconfigure(0,weight=1)
        self.table.bind("<<TreeviewSelect>>",self.select);self.table.bind("<Double-1>",self.open_file)
        ttk.Label(right,text="Media-informatie",font=("Segoe UI",14,"bold")).pack(anchor="w")
        self.preview=ttk.Label(right,text="Selecteer een foto of video",anchor="center");self.preview.pack(fill="both",expand=True,pady=8)
        self.details=tk.Text(right,height=12,wrap="word",state="disabled");self.details.pack(fill="x")
        buttons=ttk.Frame(right);buttons.pack(fill="x",pady=(10,0))
        ttk.Button(buttons,text="Openen / afspelen",command=self.open_file).pack(side="left")
        ttk.Button(buttons,text="Kaart",command=self.map).pack(side="left",padx=6)
        ttk.Button(buttons,text="Locatie zoeken",command=self.lookup).pack(side="left")
        ttk.Label(self.root,textvariable=self.status,relief="sunken",anchor="w",padding=5).pack(fill="x",side="bottom")
    def choose(self):
        p=filedialog.askdirectory(title="Kies de map met foto's en video's")
        if p:self.folder=Path(p);self.folder_label.configure(text=str(self.folder));self.scan()
    def scan(self):
        if not self.folder or not self.folder.exists():return
        self.status.set("Bestanden inventariseren…");self.root.update_idletasks();self.items=[]
        paths=list(p for p in self.folder.rglob("*") if p.is_file() and p.suffix.lower() in MEDIA_EXTS)
        for i,p in enumerate(paths,1):
            try:self.items.append(inspect_file(p))
            except (OSError,PermissionError):continue
            if i%100==0:self.status.set(f"Metadata lezen… {i} van {len(paths)}");self.root.update_idletasks()
        self.filter();self.status.set(f"Gereed: {len(self.items)} foto- en videobestanden gevonden.")
    def clear_dates(self):
        self.year.set("");self.month.set("");self.day.set("");self.filter()
    def filter(self):
        q=self.query.get().strip().lower();result=[]
        try:y=int(self.year.get()) if self.year.get().strip() else None;mo=int(self.month.get()) if self.month.get().strip() else None;d=int(self.day.get()) if self.day.get().strip() else None
        except ValueError:messagebox.showwarning("Datumfilter","Gebruik cijfers voor jaar, maand en dag.");return
        for m in self.items:
            if q and q not in m.path.name.lower() and q not in str(m.path.parent).lower() and q not in m.place.lower():continue
            dt=m.date
            if y and dt.year!=y:continue
            if mo and dt.month!=mo:continue
            if d and dt.day!=d:continue
            result.append(m)
        mode=self.sort.get()
        if mode=="Opnamedatum":result.sort(key=lambda x:x.capture or datetime.min,reverse=True)
        elif mode=="Gemeente-plaats":result.sort(key=lambda x:(x.place.lower(),x.path.name.lower()))
        elif mode=="Naam":result.sort(key=lambda x:x.path.name.lower())
        else:result.sort(key=lambda x:x.modified,reverse=True)
        self.filtered=result;self.table.delete(*self.table.get_children())
        for i,m in enumerate(result):
            dt=m.date.strftime("%d-%m-%Y %H:%M:%S");size=f"{m.size/(1024*1024):.1f} MB" if m.size>=1048576 else f"{m.size/1024:.0f} KB"
            gps=f"{m.lat:.5f}, {m.lon:.5f}" if m.lat is not None and m.lon is not None else m.place
            self.table.insert("","end",iid=str(i),values=(m.path.name,"Video" if m.is_video else "Foto",dt,size,gps))
        self.status.set(f"{len(result)} van {len(self.items)} bestanden weergegeven.")
    def sort_column(self,col):
        self.sort.set({"date":"Opnamedatum","name":"Naam","place":"Gemeente-plaats"}.get(col,"Bestandsdatum"));self.filter()
    def select(self,event=None):
        s=self.table.selection()
        if not s:return
        try:m=self.filtered[int(s[0])]
        except (ValueError,IndexError):return
        self.selected=m
        lines=[f"Bestand: {m.path.name}",f"Volledig pad: {m.path}",f"Type: {'Video' if m.is_video else 'Foto'}",f"Bestandsdatum: {m.modified:%d-%m-%Y %H:%M:%S}",f"Opnamedatum: {m.capture:%d-%m-%Y %H:%M:%S}" if m.capture else "Opnamedatum: niet beschikbaar",f"Grootte: {m.size:,} bytes"]
        if m.width and m.height:lines.append(f"Afmetingen: {m.width} × {m.height}")
        if m.camera:lines.append(f"Camera: {m.camera}")
        if m.lat is not None and m.lon is not None:lines.append(f"GPS: {m.lat:.6f}, {m.lon:.6f}")
        if m.place:lines.append(f"Plaats: {m.place}")
        self.details.configure(state="normal");self.details.delete("1.0","end");self.details.insert("1.0","\n".join(lines));self.details.configure(state="disabled")
        if m.is_video:self.preview.configure(image="",text="VIDEO\nDubbelklik of kies Openen / afspelen");self.preview.image=None
        else:
            try:
                with Image.open(m.path) as im:im.thumbnail((440,330));photo=ImageTk.PhotoImage(im.copy())
                self.preview.configure(image=photo,text="");self.preview.image=photo
            except Exception:self.preview.configure(image="",text="Voorbeeld niet beschikbaar")
    def open_file(self,event=None):
        m=self.selected
        if not m:return
        try:
            if sys.platform=="win32":os.startfile(str(m.path))
            elif sys.platform=="darwin":subprocess.Popen(["open",str(m.path)])
            else:subprocess.Popen(["xdg-open",str(m.path)])
        except Exception as e:messagebox.showerror("Openen mislukt",str(e))
    def map(self):
        m=self.selected
        if not m or m.lat is None or m.lon is None:messagebox.showinfo("Kaart","Voor dit bestand zijn geen GPS-coördinaten gevonden.");return
        webbrowser.open(f"https://www.openstreetmap.org/?mlat={m.lat}&mlon={m.lon}#map=17/{m.lat}/{m.lon}")
    def lookup(self):
        m=self.selected
        if not m or m.lat is None or m.lon is None:messagebox.showinfo("Locatie zoeken","Voor dit bestand zijn geen GPS-coördinaten gevonden.");return
        try:
            params=urllib.parse.urlencode({"lat":m.lat,"lon":m.lon,"format":"jsonv2","zoom":16,"addressdetails":1})
            req=urllib.request.Request("https://nominatim.openstreetmap.org/reverse?"+params,headers={"User-Agent":"FotoVerkennerWindows/1.0"})
            with urllib.request.urlopen(req,timeout=12) as r:data=json.loads(r.read().decode("utf-8"))
            a=data.get("address",{});town=a.get("city") or a.get("town") or a.get("village") or a.get("municipality") or ""
            road=a.get("road") or a.get("neighbourhood") or a.get("suburb") or "";region=a.get("county") or a.get("state") or ""
            m.place=", ".join(x for x in (town,road,region) if x);self.filter()
            try:i=self.filtered.index(m);self.table.selection_set(str(i));self.table.see(str(i));self.select()
            except ValueError:pass
            self.status.set("Locatie opgehaald via OpenStreetMap.")
        except Exception as e:messagebox.showwarning("Locatie zoeken",f"Plaatsnaam niet opgehaald. GPS blijft beschikbaar.\n\n{e}")

def main():
    root=tk.Tk()
    try:root.tk.call("tk","scaling",1.15)
    except Exception:pass
    App(root);root.mainloop()
if __name__=="__main__":main()
