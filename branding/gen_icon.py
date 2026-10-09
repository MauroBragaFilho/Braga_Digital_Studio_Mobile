"""Gera os drawables do icone (primeiro plano adaptativo, monocromatico, marca da splash/onboarding)
a partir da geometria de branding/bdsm-icon.svg (512x512). Rode na raiz do repositorio:
    python branding/gen_icon.py
VectorDrawable nao tem <rect rx>, entao as formas sao reescritas como paths."""
import re

RES = 'app/src/main/res/'
WHITE, RED, BLACK = '#FFFFFF', '#FF1E2D', '#000000'


def f(v):
    return ('%.2f' % v).rstrip('0').rstrip('.')


def rrect(x, y, w, h, r):
    return (f'M{x + r},{y} H{x + w - r} A{r},{r} 0 0 1 {x + w},{y + r} V{y + h - r} '
            f'A{r},{r} 0 0 1 {x + w - r},{y + h} H{x + r} A{r},{r} 0 0 1 {x},{y + h - r} '
            f'V{y + r} A{r},{r} 0 0 1 {x + r},{y} Z')


def tx(k, ox, oy):
    """Transforma coordenadas numericas de um path SVG (so comandos M L H V Q A absolutos)."""
    def conv(d):
        out = []
        for m in re.finditer(r'([MLHVQAZ])([^MLHVQAZ]*)', d):
            c, a = m.group(1), [float(n) for n in re.findall(r'-?\d+\.?\d*', m.group(2))]
            if c in 'ML' or c == 'Q':
                v = [f(a[i] * k + (ox if i % 2 == 0 else oy)) for i in range(len(a))]
            elif c == 'H':
                v = [f(a[0] * k + ox)]
            elif c == 'V':
                v = [f(a[0] * k + oy)]
            elif c == 'A':
                v = [f(a[0] * k), f(a[1] * k), f(a[2]), f(a[3]), f(a[4]), f(a[5] * k + ox), f(a[6] * k + oy)]
            else:
                v = []
            out.append(c + ' '.join(v))
        return ' '.join(out)
    return conv


BODY = rrect(76, 128, 300, 218, 32) + ' ' + rrect(101, 153, 250, 168, 14)  # corpo com furo (evenOdd)
CORNERS = ('M125 190 V177 Q125 170 132 170 H150 M302 170 H320 Q327 170 327 177 V190 '
           'M125 284 V297 Q125 304 132 304 H150 M302 304 H320 Q327 304 327 297 V284')
DOT = 'M137 220 A18 18 0 1 0 173 220 A18 18 0 1 0 137 220 Z'
STAND = 'M195 346 H257 V374 H195 Z ' + rrect(155, 374, 142, 20, 10)
ARCS = 'M394 205 A70 70 0 0 1 394 275 M420 178 A108 108 0 0 1 420 302 M447 151 A146 146 0 0 1 447 329'


def art(k, ox, oy, fill, dot, stroke_names=False, indent='    '):
    c = tx(k, ox, oy)
    n = (lambda s: f'{indent}    android:name="{s}"\n') if stroke_names else (lambda s: '')
    return (
        f'{indent}<path\n{n("body")}{indent}    android:fillColor="{fill}"\n{indent}    android:fillType="evenOdd"\n'
        f'{indent}    android:pathData="{c(BODY)}" />\n'
        f'{indent}<path\n{n("stand")}{indent}    android:fillColor="{fill}"\n{indent}    android:pathData="{c(STAND)}" />\n'
        f'{indent}<path\n{n("arcs")}{indent}    android:pathData="{c(ARCS)}"\n{indent}    android:strokeColor="{fill}"\n'
        f'{indent}    android:strokeWidth="{f(14 * k)}"\n{indent}    android:strokeLineCap="round" />\n'
        f'{indent}<path\n{n("brackets")}{indent}    android:pathData="{c(CORNERS)}"\n{indent}    android:strokeColor="{fill}"\n'
        f'{indent}    android:strokeWidth="{f(12 * k)}"\n{indent}    android:strokeLineCap="round" />\n'
        f'{indent}<path\n{n("dot")}{indent}    android:fillColor="{dot}"\n{indent}    android:pathData="{c(DOT)}" />\n')


HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- GERADO por branding/gen_icon.py a partir de branding/bdsm-icon.svg. {what} -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

# Adaptativo: centraliza o conteudo (bbox ~ x 76..454, y 128..394, centro 261,261) na area segura (k=0.15).
K = 0.15
OX = 54 - 261 * K
OY = 54 - 261 * K
fg = HEAD.format(what='Primeiro plano do icone adaptativo (fundo preto em @color/ic_launcher_background).') + art(K, OX, OY, WHITE, RED) + '</vector>\n'
mono = HEAD.format(what='Icone tematico (Android 13+): uma cor so.') + art(K, OX, OY, BLACK, BLACK) + '</vector>\n'

# Marca da splash/onboarding: o icone inteiro (ladrilho preto + arte) em 108x108, animavel por nome.
KB = 108 / 512
tile = f'    <path\n        android:name="tile"\n        android:fillColor="{BLACK}"\n        android:pathData="{rrect(0, 0, 108, 108, 20)}" />\n'
brand = (HEAD.format(what='Marca para splash e onboarding (o proprio icone). Partes nomeadas animadas em res/animator.') + tile +
         '    <group\n        android:name="letters"\n        android:pivotX="54"\n        android:pivotY="54"\n        android:scaleX="1"\n        android:scaleY="1">\n' +
         art(KB, 0, 0, WHITE, RED, stroke_names=True, indent='        ') + '    </group>\n</vector>\n')

for name, text in (('ic_launcher_foreground.xml', fg), ('ic_launcher_monochrome.xml', mono), ('ic_brand_mark.xml', brand)):
    open(RES + 'drawable/' + name, 'w', encoding='utf-8', newline='\n').write(text)

# animadores da splash: o grupo "letters" cresce de 0,55 a 0,72 (a mascara circular do sistema corta o que passa de ~2/3 do icone); a marca estatica (onboarding) fica em 1,0
for ax in ('x', 'y'):
    p = RES + f'animator/splash_letters_{ax}.xml'
    s = open(p, encoding='utf-8', newline='').read()
    s = re.sub(r'android:valueFrom="[0-9.]+"', 'android:valueFrom="0.55"', s)
    s = re.sub(r'android:valueTo="[0-9.]+"', 'android:valueTo="0.72"', s)
    open(p, 'w', encoding='utf-8', newline='').write(s)

# fundo do icone adaptativo
p = RES + 'values/colors.xml'
s = open(p, encoding='utf-8', newline='').read()
s = re.sub(r'(<color name="ic_launcher_background">)#[0-9A-Fa-f]{6}(</color>)', r'\1#000000\2', s)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
