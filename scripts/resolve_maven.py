import os, urllib.request, urllib.error, xml.etree.ElementTree as ET, pathlib, re, sys
M2 = pathlib.Path.home() / ".m2" / "repository"
REPOS = ["https://repo1.maven.org/maven2/", "https://repo.clojars.org/"]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
def fetch(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "maven-resolver"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read()
    except urllib.error.HTTPError:
        return None
    except Exception as e:
        print("fetch err", url, e, flush=True); return None
def group_path(g): return g.replace(".", "/")
seen=set(); queue=[]
def add(g,a,v,exclusions=frozenset()):
    # Maven nearest-wins: first (shallowest, BFS) version of an artifact wins.
    key=(g,a)
    if key in seen: return
    seen.add(key); queue.append((g,a,v,exclusions))
def parse_pom(data):
    root=ET.fromstring(data)
    def local(tag): return tag.split("}")[-1]
    def child(el, name):
        for ch in el:
            if local(ch.tag)==name: return ch
        return None
    def children(el, name):
        return [ch for ch in el if local(ch.tag)==name]
    def txt(el, tag):
        e=child(el, tag)
        return e.text.strip() if e is not None and e.text else None
    props={}
    props_el=child(root, "properties")
    if props_el is not None:
        for ch in props_el:
            props[local(ch.tag)]=ch.text.strip() if ch.text else ""
    parent=child(root, "parent")
    parent_coord=None
    if parent is not None:
        parent_coord=(txt(parent,"groupId"), txt(parent,"artifactId"), txt(parent,"version"))
    deps=[]
    deps_el=child(root, "dependencies")
    if deps_el is not None:
        for d in children(deps_el, "dependency"):
            g=txt(d,"groupId"); a=txt(d,"artifactId"); v=txt(d,"version"); scope=txt(d,"scope") or "compile"; opt=txt(d,"optional")
            excl=set()
            excl_el=child(d, "exclusions")
            if excl_el is not None:
                for ex in children(excl_el, "exclusion"):
                    excl.add((txt(ex,"groupId"), txt(ex,"artifactId")))
            deps.append((g,a,v,scope,opt=="true",excl))
    mgmt={}
    dm=child(root, "dependencyManagement")
    if dm is not None:
        mgmt_el=child(dm, "dependencies")
        if mgmt_el is not None:
            for d in children(mgmt_el, "dependency"):
                g=txt(d,"groupId"); a=txt(d,"artifactId"); v=txt(d,"version")
                if g and a and v: mgmt[(g,a)]=v
    return {"props":props,"parent":parent_coord,"deps":deps,"mgmt":mgmt}
def resolve_props(s, props, parent_props):
    if s is None: return None
    def repl(m):
        k=m.group(1)
        return props.get(k, parent_props.get(k, m.group(0)))
    for _ in range(10):
        ns=re.sub(r"\$\{([^}]+)\}", repl, s)
        if ns==s: break
        s=ns
    return s
def process(g,a,v,exclusions):
    dest=M2/group_path(g)/a/v; dest.mkdir(parents=True, exist_ok=True)
    pom_path=dest/f"{a}-{v}.pom"
    if pom_path.exists():
        data=pom_path.read_bytes()
    else:
        data=None
        for repo in REPOS:
            data=fetch(f"{repo}{group_path(g)}/{a}/{v}/{a}-{v}.pom")
            if data: break
        if not data:
            print(f"NO POM {g}:{a}:{v}", flush=True); return
        pom_path.write_bytes(data)
    for classifier in [None, "aot"]:
        fname=f"{a}-{v}-{classifier}.jar" if classifier else f"{a}-{v}.jar"
        if (dest/fname).exists(): continue
        jar=None
        for repo in REPOS:
            jar=fetch(f"{repo}{group_path(g)}/{a}/{v}/{fname}")
            if jar: break
        if jar:
            (dest/fname).write_bytes(jar); print(f"jar {g}:{a}:{v}:{classifier or ''} {len(jar)}", flush=True)
    try:
        info=parse_pom(data)
    except Exception as e:
        print("pom parse err", g,a,e, flush=True); return
    props=dict(info["props"]); parent_props={}
    if info["parent"]:
        pg,pa,pv=info["parent"]
        pdest=M2/group_path(pg)/pa/pv; pdest.mkdir(parents=True, exist_ok=True)
        ppom=pdest/f"{pa}-{pv}.pom"
        if ppom.exists():
            pdata=ppom.read_bytes()
        else:
            pdata=None
            for repo in REPOS:
                pdata=fetch(f"{repo}{group_path(pg)}/{pa}/{pv}/{pa}-{pv}.pom")
                if pdata: break
            if pdata: ppom.write_bytes(pdata)
        if pdata:
            try:
                pinfo=parse_pom(pdata); parent_props=dict(pinfo["props"]); props={**parent_props, **props}
            except Exception: pass
    for (dg,da,dv,scope,opt,excl) in info["deps"]:
        if scope=="test": continue
        if opt: continue
        if (dg,da) in exclusions: continue
        dv2=resolve_props(dv, props, parent_props)
        if not dv2:
            dv2=info["mgmt"].get((dg,da))
            if dv2: dv2=resolve_props(dv2, props, parent_props)
        if not dv2:
            print(f"  no version for {dg}:{da} (from {g}:{a})", flush=True); continue
        dg=resolve_props(dg, props, parent_props); da=resolve_props(da, props, parent_props)
        add(dg,da,dv2, frozenset(excl))
add("thheller","shadow-cljs","3.5.11")
add("reagent","reagent","1.2.0")
idx=0
while idx < len(queue):
    g,a,v,excl=queue[idx]; idx+=1
    print(f"[{idx}/{len(queue)}] {g}:{a}:{v}", flush=True)
    process(g,a,v,excl)
print("total", len(seen), flush=True)
