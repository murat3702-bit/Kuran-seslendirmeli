#!/usr/bin/env python3
"""
Silinen meal ve makaleleri uygulama PAKETİNDEN kalıcı olarak çıkarır (hard delete).

Neden gerekli?  Pakete gömülü meal/makale verisi APK'nın içindedir; uygulama çalışırken APK değiştirilemez.
Admin panelinde silinen bir kayıt uygulamada hemen kalkar, ama "uygulama verisini sıfırla" yapılırsa APK'daki kopya
yeniden okunur. Bu araç, silinenlerin kimliklerini bir yedek dosyasından alıp index.html içindeki verilerden
(META, MB, AB) gerçekten siler. Yeni APK derlendikten sonra o içerik hiçbir şekilde geri gelmez.

Kullanım:
    1) Uygulamada Kayıtlar > Yedekle (Dosya Kaydet) ile yedek al.
    2) python tools/prune_deleted.py yedek.json            (yalnızca ne silineceğini gösterir)
       python tools/prune_deleted.py yedek.json --apply    (index.html'i günceller; .bak yedeği alır)
    3) APK'yı yeniden derle.
Yerleşik (çekirdek) mealler silinemez: diyanet, elmali, edip, ynuri.
"""
import base64, gzip, json, re, shutil, sys, os

HTML = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'www', 'index.html')
CORE = {'diyanet', 'elmali', 'edip', 'ynuri'}

def die(msg):
    print('HATA:', msg); sys.exit(1)

def find_line(s, prefix):
    i = s.find(prefix)
    if i < 0: die(prefix + ' bulunamadı')
    j = s.find('\n', i)
    return i, j, s[i:j]

def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    apply_ = '--apply' in sys.argv
    if len(args) != 1: die('Kullanım: prune_deleted.py yedek.json [--apply]')
    try: bk = json.load(open(args[0], encoding='utf-8-sig'))
    except Exception as e: die('Yedek okunamadı: %s' % e)
    adm = bk.get('admin') if isinstance(bk.get('admin'), dict) else bk
    hidden = [x for x in (adm.get('hidden') or []) if isinstance(x, str)]
    art_del = [x for x in ((adm.get('arts') or {}).get('del') or []) if isinstance(x, str)]

    s = open(HTML, encoding='utf-8').read()
    m = re.search(r'const META=(\{.*?\});\nconst MB=', s, re.S)
    if not m: die('META bulunamadı')
    META = json.loads(m.group(1))
    tr_ids = {t['id'] for t in META['tr']}; art_ids = {a['id'] for a in META['arts']}

    bad = [x for x in hidden if x in CORE]
    if bad: die('Yerleşik meal silinemez: ' + ', '.join(bad))
    tr_rm = [x for x in hidden if x in tr_ids]
    art_rm = [x for x in art_del if x in art_ids]
    print('Paketten çıkarılacak mealler  :', tr_rm or '—')
    print('Paketten çıkarılacak makaleler:', art_rm or '—')
    skipped = [x for x in hidden + art_del if x not in tr_ids | art_ids]
    if skipped: print('(Pakette zaten olmayanlar atlandı:', skipped, ')')
    if not (tr_rm or art_rm): print('Yapılacak bir şey yok.'); return
    if not apply_: print('\nDeğişiklik yapılmadı. Uygulamak için --apply ekleyin.'); return

    ib, jb, lb = find_line(s, 'const MB=')
    ia, ja, la = find_line(s, 'const AB=')
    MB = json.loads(lb[len('const MB='):].rstrip(';')); AB = json.loads(la[len('const AB='):].rstrip(';'))
    META['tr'] = [t for t in META['tr'] if t['id'] not in tr_rm]
    META['arts'] = [a for a in META['arts'] if a['id'] not in art_rm]
    for x in tr_rm: MB.pop(x, None)
    for x in art_rm: AB.pop(x, None)
    tail_b = ';' if lb.endswith(';') else ''; tail_a = ';' if la.endswith(';') else ''
    new = s[:m.start(1)] + json.dumps(META, ensure_ascii=False) + s[m.end(1):]
    # satır konumları META değişince kaydı; yeniden bul
    ib, jb, _ = find_line(new, 'const MB=')
    new = new[:ib] + 'const MB=' + json.dumps(MB, ensure_ascii=False) + tail_b + new[jb:]
    ia, ja, _ = find_line(new, 'const AB=')
    new = new[:ia] + 'const AB=' + json.dumps(AB, ensure_ascii=False) + tail_a + new[ja:]
    shutil.copyfile(HTML, HTML + '.bak')
    open(HTML, 'w', encoding='utf-8').write(new)
    print('\nTamamlandı. Eski dosya: index.html.bak  |  boyut: %.1f MB -> %.1f MB' % (len(s.encode()) / 1e6, len(new.encode()) / 1e6))
    print('Şimdi APK\'yı yeniden derleyin; silinen içerik artık pakette yok.')

if __name__ == '__main__':
    main()
