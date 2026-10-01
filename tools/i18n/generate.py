#!/usr/bin/env python3
"""Genera app/src/main/res/values*/strings.xml a partir de tools/i18n/<idioma>.txt (clave|texto, \\n = salto de línea).

Español es el idioma por defecto (values/). Valida que todas las claves y marcadores (%1$s, %1$d, %%) coincidan.
Uso: python3 tools/i18n/generate.py
"""
import re, sys, pathlib

HERE = pathlib.Path(__file__).parent
RES = HERE.parent.parent / "app/src/main/res"
LANGS = {"es": "values", "en": "values-en", "fr": "values-fr", "de": "values-de", "pt": "values-pt",
         "zh": "values-zh", "ja": "values-ja", "ru": "values-ru", "hi": "values-hi"}

PLURALS = {
    "clip_count": {
        "es": {"one": "%1$d clip", "many": "%1$d clips", "other": "%1$d clips"},
        "en": {"one": "%1$d clip", "other": "%1$d clips"},
        "fr": {"one": "%1$d clip", "many": "%1$d clips", "other": "%1$d clips"},
        "de": {"one": "%1$d Clip", "other": "%1$d Clips"},
        "pt": {"one": "%1$d clipe", "many": "%1$d clipes", "other": "%1$d clipes"},
        "zh": {"other": "%1$d 个剪辑"},
        "ja": {"other": "%1$d 件のクリップ"},
        "ru": {"one": "%1$d клип", "few": "%1$d клипа", "many": "%1$d клипов", "other": "%1$d клипа"},
        "hi": {"one": "%1$d क्लिप", "other": "%1$d क्लिप"},
    },
    "clips_found": {
        "es": {"one": "%1$d clip sugerido", "many": "%1$d clips sugeridos", "other": "%1$d clips sugeridos"},
        "en": {"one": "%1$d suggested clip", "other": "%1$d suggested clips"},
        "fr": {"one": "%1$d clip suggéré", "many": "%1$d clips suggérés", "other": "%1$d clips suggérés"},
        "de": {"one": "%1$d vorgeschlagener Clip", "other": "%1$d vorgeschlagene Clips"},
        "pt": {"one": "%1$d clipe sugerido", "many": "%1$d clipes sugeridos", "other": "%1$d clipes sugeridos"},
        "zh": {"other": "推荐 %1$d 个剪辑"},
        "ja": {"other": "おすすめクリップ %1$d 件"},
        "ru": {"one": "%1$d рекомендуемый клип", "few": "%1$d рекомендуемых клипа", "many": "%1$d рекомендуемых клипов", "other": "%1$d рекомендуемого клипа"},
        "hi": {"one": "%1$d सुझाया गया क्लिप", "other": "%1$d सुझाए गए क्लिप"},
    },
}

def load(lang):
    d = {}
    for line in (HERE / f"{lang}.txt").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        k, v = line.split("|", 1)
        d[k] = v
    return d

def esc(s):
    s = s.replace("\\n", "\n")              # \n del .txt -> salto real
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace("@", "\\@")
    return s.replace("\n", "\\n")

def ph(s):
    return sorted(re.findall(r"%\d\$[sd]|%%", s))

def main():
    data = {l: load(l) for l in LANGS}
    base = data["es"]
    ok = True
    for l, d in data.items():
        if list(d) != list(base):
            miss = set(base) - set(d); extra = set(d) - set(base)
            print(f"[{l}] claves distintas. faltan={sorted(miss)} sobran={sorted(extra)}"); ok = False
        for k in set(base) & set(d):
            if ph(d[k]) != ph(base[k]):
                print(f"[{l}] marcadores distintos en {k}: {ph(d[k])} vs {ph(base[k])}"); ok = False
    if not ok:
        sys.exit(1)
    for l, folder in LANGS.items():
        out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        if l == "es":
            out.append('    <string name="app_name" translatable="false">ShortsMaker Viral</string>')
        for k, v in data[l].items():
            out.append(f'    <string name="{k}">{esc(v)}</string>')
        for name, per in PLURALS.items():
            out.append(f'    <plurals name="{name}">')
            for q, t in per[l].items():
                out.append(f'        <item quantity="{q}">{esc(t)}</item>')
            out.append("    </plurals>")
        out.append("</resources>")
        d = RES / folder
        d.mkdir(parents=True, exist_ok=True)
        (d / "strings.xml").write_text("\n".join(out) + "\n", encoding="utf-8")
    print("OK:", len(base), "cadenas x", len(LANGS), "idiomas")

main()
