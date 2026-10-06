"""Gera o primeiro plano (e o monocromático) do ícone adaptativo a partir da geometria do SVG.
VectorDrawable não suporta skew, então as coordenadas são transformadas aqui."""
import math, sys
K = 0.28            # 256-space -> viewport 108
TAN = math.tan(math.radians(-12))
def sk(x, y): return (x + 24 + y * TAN, y)
def tr(p):
    x, y = sk(*p)
    return ((x - 128) * K + 54, (y - 128) * K + 54)
def cub(c):  # semicírculo com 1 cubic: (x0,y0)->(x0,y0+2r), bojo para a direita
    pass
def f(v): return ('%.2f' % v).rstrip('0').rstrip('.')
def pt(p): q = tr(p); return f(q[0]) + ',' + f(q[1])

def bowl(x, y0, x_end_straight, r, bulge):  # linha horizontal até x_end, semicírculo, volta
    return [('M', [(x, y0)]), ('L', [(x_end_straight, y0)]),
            ('C', [(x_end_straight + bulge, y0), (x_end_straight + bulge, y0 + 2*r), (x_end_straight, y0 + 2*r)]),
            ('L', [(x, y0 + 2*r)])]
B = [('M', [(34, 94)]), ('L', [(34, 134)])] + bowl(34, 94, 78, 10, 13.33)[0:0]
B_paths = [
    [('M', [(34, 94)]), ('L', [(34, 134)])],
    bowl(34, 94, 78, 10, 13.33),
    bowl(34, 114, 82, 10, 13.33),
]
D_paths = [
    [('M', [(110, 94)]), ('L', [(110, 134)])],
    bowl(110, 94, 128, 20, 26.67),
]
S_path = [('M', [(222, 103)]), ('C', [(222, 90), (166, 88), (166, 106)]),
          ('C', [(166, 122), (224, 116), (224, 129)]), ('C', [(224, 142), (166, 142), (164, 132)])]
def d(cmds): return ' '.join(c + ' ' + ' '.join(pt(p) for p in pts) for c, pts in cmds)
def dd(groups): return ' '.join(d(g) for g in groups)
bracket = ('M16 62 L16 36 C16 24.95 24.95 16 36 16 L62 16 '
           'M194 16 L220 16 C231.05 16 240 24.95 240 36 L240 62 '
           'M240 194 L240 220 C240 231.05 231.05 240 220 240 L194 240 '
           'M62 240 L36 240 C24.95 240 16 231.05 16 220 L16 194')
import re
def tb(s):
    out = []
    for m in re.finditer(r'([MLC])([^MLC]*)', s):
        c = m.group(1); nums = list(map(float, m.group(2).split()))
        pts = [(nums[i], nums[i+1]) for i in range(0, len(nums), 2)]
        out.append(c + ' ' + ' '.join(f((tr_nosk(p))[0]) + ',' + f((tr_nosk(p))[1]) for p in pts))
    return ' '.join(out)
def tr_nosk(p): return ((p[0]-128)*K+54, (p[1]-128)*K+54)
dot = (tr_nosk((128, 58)), 6.5 * K)

def vector(red, light, mono=False):
    cx, cy = dot[0]; r = dot[1]
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- GERADO por branding/gen_launcher.py a partir de branding/bdsm-icon.svg: visor + monograma BDS
     (itálico, negrito). {'Ícone temático (Android 13+): uma cor só.' if mono else 'Primeiro plano do ícone adaptativo, sobre o fundo escuro da marca.'} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{tb(bracket)}"
        android:strokeColor="{red}"
        android:strokeWidth="{f(3.2*K)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="{red}"
        android:pathData="M{f(cx)},{f(cy)} m-{f(r)},0 a{f(r)},{f(r)} 0 1,0 {f(2*r)},0 a{f(r)},{f(r)} 0 1,0 -{f(2*r)},0" />
    <path
        android:pathData="{dd(B_paths + D_paths)}"
        android:strokeColor="{light}"
        android:strokeWidth="{f(12*K)}"
        android:strokeLineCap="butt"
        android:strokeLineJoin="round" />
    <path
        android:pathData="{d(S_path)}"
        android:strokeColor="{red}"
        android:strokeWidth="{f(12*K)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
'''
res = 'app/src/main/res/drawable/'
open(res + 'ic_launcher_foreground.xml', 'w', encoding='utf-8', newline='\n').write(vector('#E6003E', '#ECECEF'))
open(res + 'ic_launcher_monochrome.xml', 'w', encoding='utf-8', newline='\n').write(vector('#000000', '#000000', True))
print('ok')
