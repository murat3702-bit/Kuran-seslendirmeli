#!/usr/bin/env python3
"""
Yazar fotoğraflarını uygulama PAKETİNE (APK) gömer; böylece "Depolamayı temizle" / uygulama verisi sıfırlama
sonrasında da fotoğraflar kaybolmaz.

Neden gerekli?  Yönetici panelinden eklenen fotoğraflar telefonun uygulama verisinde (localStorage) durur; Android
bu veriyi silince onlar da gider. APK içindeki dosyalar ise silinmez. Bu araç yedek dosyasındaki fotoğrafları
index.html içindeki PHOTOS sabitine yazar.

Kullanım:
    1) Panelde fotoğrafları ekle, Kayıtlar > Yedekle (Dosya Kaydet) ile yedek al.
    2) python tools/bake_photos.py yedek.json            (ön izleme)
       python tools/bake_photos.py yedek.json --apply    (index.html'i günceller; index.html.bak alır)
    3) APK'yı yeniden derle.
Araç yedekteki fotoğrafların TAMAMINI yazar (önceki gömülü liste yerine geçer); fotoğrafı olmayan yazar listede olmaz.
"""
import json, os, re, shutil, sys

HTML = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'www', 'index.html')
OK = re.compile(r'^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$')
MAX = 150000

def die(m):
    print('HATA:', m); sys.exit(1)

def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(args) != 1: die('Kullanım: bake_photos.py yedek.json [--apply]')
    try: bk = json.load(open(args[0], encoding='utf-8-sig'))
    except Exception as e: die('Yedek okunamadı: %s' % e)
    adm = bk.get('admin') if isinstance(bk.get('admin'), dict) else bk
    bios = adm.get('bios') if isinstance(adm.get('bios'), dict) else {}
    photos = {}
    for k, b in bios.items():
        if isinstance(b, dict) and isinstance(b.get('photo'), str):
            bid = b.get('id') or k
            if len(b['photo']) <= MAX and OK.match(b['photo']): photos[bid] = b['photo']
            else: print('(Atlandı: geçersiz/çok büyük fotoğraf ->', bid, ')')
    print('Gömülecek fotoğraf sayısı:', len(photos))
    for k, v in photos.items(): print('  -', k, '(%d KB)' % (len(v) // 1024))
    if not photos: die('Yedekte fotoğraf yok.')
    if '--apply' not in sys.argv:
        print('\nDeğişiklik yapılmadı. Uygulamak için --apply ekleyin.'); return
    s = open(HTML, encoding='utf-8').read()
    m = re.search(r'/\*PHOTOS-BEGIN\*/.*?/\*PHOTOS-END\*/', s, re.S)
    if not m: die('PHOTOS işaretleri index.html içinde bulunamadı')
    shutil.copyfile(HTML, HTML + '.bak')
    block = '/*PHOTOS-BEGIN*/const PHOTOS=' + json.dumps(photos, ensure_ascii=False, separators=(',', ':')) + ';/*PHOTOS-END*/'
    s = s[:m.start()] + block + s[m.end():]
    open(HTML, 'w', encoding='utf-8').write(s)
    print('Tamam. index.html güncellendi (yedek: index.html.bak). APK\'yı yeniden derleyin.')

main()
