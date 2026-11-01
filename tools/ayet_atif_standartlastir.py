#!/usr/bin/env python3
"""
Kavramlar ve makalelerdeki ayet atıflarını tek biçime çevirir:  Sure/Ayet

    Bakara 120          -> Bakara/120
    Bakara 120 140      -> Bakara/120-140
    Bakara 120–140      -> Bakara/120-140
    Neml 4              -> Neml/4
    Fatiha suresi 3     -> Fatiha/3
    Bakara suresinin 196. ayeti -> Bakara/196
    Bakara 2:255        -> Bakara/255
    Nisa; 58, 135       -> Nisa/58, 135
    (zaten standart olan Bakara/120-140 gibi atıflara dokunulmaz)

Taranan veri:
    app/src/main/assets/www/kavramlar.json      (her kavramın "text" alanı)
    app/src/main/assets/www/index.html          (gömülü makaleler: AB)

Kullanım:
    python tools/ayet_atif_standartlastir.py                 (yalnızca ne değişeceğini gösterir)
    python tools/ayet_atif_standartlastir.py --uygula        (dosyaları günceller; yedekleri tools/yedek/ altına alır)
    python tools/ayet_atif_standartlastir.py --rapor r.txt   (tam raporu dosyaya yazar)
    python tools/ayet_atif_standartlastir.py --isimler kanonik
        sûre adını uygulamanın kendi yazımıyla (Fâtiha, Nisâ ...) yazar; varsayılan: metinde nasıl yazılmışsa öyle.

Güvenlik kuralları (yanlış dönüşümü önlemek için):
  * Ayet sayısı o sûrenin ayet sayısını aşıyorsa dokunulmaz.
  * Sade "Ad Sayı" biçimi (Bakara 120) yalnızca sûre adı sıradan bir sözcük/özel ad olmayan sûrelerde otomatik
    çevrilir. Nuh, Hud, Nur, Tin, Fil, Kaf, Saf, Hac, Cin, İnsan... gibi adlarda "(Nur 5)" ya da "bkz. Nur 5" gibi
    açık bir atıf bağlamı yoksa dokunulmaz ve "İNCELEME" listesine yazılır.
  * Etiketlerle bölünmüş atıflar (ör. Bakara/<sup>5</sup>) ve "ayetinde / suresinde" gibi çekimli yapılar
    otomatik değiştirilmez, "İNCELEME" listesine yazılır.
"""
import base64, gzip, json, os, re, shutil, sys, argparse

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
WWW = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'www')
HTML = os.path.join(WWW, 'index.html')
KVJ = os.path.join(WWW, 'kavramlar.json')

# ---------------------------------------------------------------- sûre adları (uygulamadaki KV_ALIAS ile aynı)
ALIAS = {
    1: ['fatiha'], 2: ['bakara'], 3: ['al_i_imran'], 4: ['nisa'], 5: ['maide'], 6: ['en_am'], 7: ['a_raf'], 8: ['enfal'],
    9: ['tevbe', 'tovbe'], 10: ['yunus'], 11: ['hud'], 12: ['yusuf'], 13: ['ra_d'], 14: ['ibrahim'], 15: ['hicr'], 16: ['nahl'],
    17: ['isra'], 18: ['kehf'], 19: ['meryem'], 20: ['ta_ha'], 21: ['enbiya'], 22: ['hacc', 'hac'], 23: ['mu_minun'], 24: ['nur'],
    25: ['furkan'], 26: ['su_ara'], 27: ['neml'], 28: ['kasas'], 29: ['ankebut'], 30: ['rum'], 31: ['lokman'], 32: ['secde'],
    33: ['ahzab'], 34: ['sebe'], 35: ['fatir'], 36: ['ya_sin'], 37: ['saffat'], 38: ['sad'], 39: ['zumer'], 40: ['mu_min', 'gafir'],
    41: ['fussilet'], 42: ['sura'], 43: ['zuhruf'], 44: ['duhan'], 45: ['casiye'], 46: ['ahkaf'], 47: ['muhammed'], 48: ['fetih'],
    49: ['hucurat'], 50: ['kaf'], 51: ['zariyat'], 52: ['tur'], 53: ['necm'], 54: ['kamer'], 55: ['rahman'], 56: ['vakia'],
    57: ['hadid'], 58: ['mucadele'], 59: ['hasr'], 60: ['mumtehine', 'mumtehin'], 61: ['saff', 'saf'], 62: ['cuma'], 63: ['munafikun'],
    64: ['tegabun'], 65: ['talak'], 66: ['tahrim'], 67: ['mulk'], 68: ['kalem'], 69: ['hakka'], 70: ['mearic', 'me_aric'],
    71: ['nuh'], 72: ['cinn', 'cin'], 73: ['muzzemmil'], 74: ['muddessir'], 75: ['kiyamet', 'kiyame'], 76: ['insan', 'dehr'],
    77: ['murselat'], 78: ['nebe'], 79: ['naziat'], 80: ['abese'], 81: ['tekvir'], 82: ['infitar'], 83: ['mutaffifin', 'muttaffifin'],
    84: ['insikak'], 85: ['buruc'], 86: ['tarik'], 87: ['a_la'], 88: ['gasiye'], 89: ['fecr'], 90: ['beled'], 91: ['sems'],
    92: ['leyl'], 93: ['duha'], 94: ['insirah', 'serh'], 95: ['tin'], 96: ['alak'], 97: ['kadir', 'kadr'], 98: ['beyyine'],
    99: ['zilzal'], 100: ['adiyat'], 101: ['karia'], 102: ['tekasur'], 103: ['asr'], 104: ['humeze'], 105: ['fil'],
    106: ['kureys'], 107: ['maun'], 108: ['kevser'], 109: ['kafirun'], 110: ['nasr'], 111: ['tebbet', 'mesed'],
    112: ['ihlas'], 113: ['felak'], 114: ['nas'],
}
AYET_SAYISI = [7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77, 227,
               93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78,
               96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25,
               22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6]

# Sade "Ad Sayı" yazımında yanlış eşleşme riski olan adlar (sıradan sözcük / peygamber adı / sayıyla sık geçen ad)
RISKLI = {'tin', 'fil', 'sad', 'kaf', 'nas', 'nur', 'saf', 'hac', 'cin', 'cinn', 'insan', 'dehr', 'kadir', 'kadr', 'asr', 'duha',
          'leyl', 'alak', 'kamer', 'tur', 'fecr', 'beled', 'kalem', 'fetih', 'mulk', 'isra', 'hasr', 'cuma', 'talak', 'secde',
          'sura', 'sems', 'nebe', 'tarik', 'nasr', 'serh', 'rum', 'mu_min', 'kiyamet', 'kiyame', 'hud', 'nuh', 'yusuf', 'yunus',
          'muhammed', 'ibrahim', 'lokman', 'meryem', 'furkan', 'sebe', 'fatir', 'mesed'}
# sayıdan hemen sonra bunlardan biri geliyorsa ("Nuh 3 gün") sade atıf sayılmaz
BIRIM_RE = re.compile(r"[\s\u00a0]*(?:gün|yıl|sene|ay|saat|kez|defa|kere|kişi|asır|yüzyıl|tane|adet|kadar|yaş|dakika|sefer|nesil|bin|milyon|yüz|kat|kuruş|lira|dönem|bölüm|madde|sayfa|sıra|numara|nolu)(?![\w])", re.I)

TR = {'â': 'a', 'Â': 'a', 'î': 'i', 'Î': 'i', 'û': 'u', 'Û': 'u', 'ö': 'o', 'Ö': 'o', 'ü': 'u', 'Ü': 'u', 'ş': 's', 'Ş': 's',
      'ç': 'c', 'Ç': 'c', 'ğ': 'g', 'Ğ': 'g', 'ı': 'i', 'İ': 'i', 'I': 'i'}
CLS = {'a': 'aAâÂ', 'c': 'cCçÇ', 'g': 'gGğĞ', 'i': 'iIıİîÎ', 'o': 'oOöÖ', 's': 'sSşŞ', 'u': 'uUüÜûÛ'}
SEP = r"[\s\u00a0'’‘ʿʻ`´\-–]*"
WS = r"[\s\u00a0]"
DASH = r"[-–—]"


def key(s):
    o = ''
    for ch in s:
        m = TR.get(ch, ch.lower())
        if 'a' <= m <= 'z':
            o += m
    return o


def pat(a):
    return ''.join(SEP if c == '_' else '[' + CLS.get(c, c + c.upper()) + ']' for c in a)


BY_KEY, ALTS = {}, []
for n, al in ALIAS.items():
    for a in al:
        BY_KEY[a.replace('_', '')] = n
        ALTS.append(a)
ALTS.sort(key=lambda x: -len(x.replace('_', '')))
NAME_RE = re.compile(r"(?<![^\W\d_])(" + '|'.join(pat(a) for a in ALTS) + r")(?=" + WS + r"*[/;]|[\s\u00a0,(]*\d|" + WS + r"+[sSşŞ][uUûÛ][rR][eE])")
SURE_RE = re.compile(WS + r"+[sS][uUûÛ][rR][eE](?P<ek>[\w’'‘]{0,12})(?!\w)")
NUM = r"(\d{1,3})(?!\d)"
# ayet sözcüğü: yalnızca yalın / belirtme / çoğul biçimler silinir (ayetinde, ayetine ... incelemeye gider)
AYET_OK = re.compile(WS + r"*\.?" + WS + r"*[âaAÂ][yY][eEiI][tT](?:ler|leri|i)?(?![\w’'])", re.I)
AYET_ANY = re.compile(r"(" + WS + r"*\.?" + WS + r"*)[âaAÂ][yY][eEiI][tT]\w*", re.I)


def build_ref(text, pos, sn, mx, mode):
    """Ad'dan sonra gelen ayet bölümünü çözer -> (items, end, flags) ya da None.
    mode: 'slash' | 'colon' | 'sure' | 'ayet' | 'bare'"""
    items, flags = [], []
    # 1) Bakara/255 · Nisa; 58, 135 · Bakara/2/255 · Bakara/2:255
    m = re.compile(WS + r"*[/;]" + WS + r"*" + NUM).match(text, pos)
    if m:
        v = int(m.group(1)); p = m.end()
        mm = re.compile(r"[:/]" + NUM).match(text, p)
        if v == sn and mm and 1 <= int(mm.group(1)) <= mx:
            v = int(mm.group(1)); p = mm.end()
        if not (1 <= v <= mx):
            return None
        items.append([v, None])
        return more(text, p, sn, mx, items, flags, 'slash')
    # 2) Bakara 2:255 · Bakara, 2/255 · Ahzab, 33/63  (sûre no : ayet no)
    m = re.compile(r"[\s\u00a0,(]*" + NUM + r"[:/]" + NUM).match(text, pos)
    if m and int(m.group(1)) == sn and 1 <= int(m.group(2)) <= mx:
        items.append([int(m.group(2)), None])
        return more(text, m.end(), sn, mx, items, flags, 'colon')
    # sûre sözcüğü
    sm = SURE_RE.match(text, pos)
    if sm:
        ek = re.sub(r"[’'‘]", '', sm.group('ek').lower())
        if ek not in ('', 'si', 'sinin', 'i'):  # suresinde, suresine ... -> incelemeye
            nm = re.compile(WS + r"+(?:\d|.{0,3}\d)").match(text, sm.end())
            if nm:
                return ('REVIEW', sm.end(), 'çekimli sûre sözcüğü (%s)' % sm.group(0).strip())
            return None
        p = sm.end()
        m2 = re.compile(r"[\s\u00a0,(]*" + NUM + r"[:/]" + NUM).match(text, p)
        if m2 and int(m2.group(1)) == sn and 1 <= int(m2.group(2)) <= mx:
            items.append([int(m2.group(2)), None]); p = m2.end()
            return more(text, p, sn, mx, items, flags, 'sure')
        m2 = re.compile(WS + r"*[,(]?" + WS + r"*" + NUM).match(text, p)
        if m2 and 1 <= int(m2.group(1)) <= mx:
            items.append([int(m2.group(1)), None]); p = m2.end()
            r = more(text, p, sn, mx, items, flags, 'sure')
            return eat_ayet(text, r, flags)
        return None  # "Bakara suresi" (ayet numarası yok): dokunma
    # Ad 196. ayet   /   Ad 196 ayet
    m = re.compile(WS + r"+" + NUM).match(text, pos)
    if not m:
        m = re.compile(r"[ \u00a0]*,[ \u00a0]*" + NUM).match(text, pos)
    if m and 1 <= int(m.group(1)) <= mx:
        v = int(m.group(1)); p = m.end()
        items.append([v, None])
        r = more(text, p, sn, mx, items, flags, 'bare')
        if r is None:
            return None
        items, p, flags = r
        am = AYET_OK.match(text, p)
        if am:
            return items, am.end(), flags + ['ayet']
        am = AYET_ANY.match(text, p)
        if am:                                   # "Bakara 196. ayetinde" -> "Bakara/196 ayetinde" (çekimli sözcük korunur)
            return items, am.start() + len(am.group(1)), flags + ['kal-ayet']
        return items, p, flags + ['bare']
    return None


def eat_ayet(text, r, flags):
    if r is None or r[0] == 'REVIEW':
        return r
    items, p, fl = r
    am = AYET_OK.match(text, p)
    if am:
        return items, am.end(), fl + ['ayet']
    am = AYET_ANY.match(text, p)
    if am:
        return items, am.start() + len(am.group(1)), fl + ['kal-ayet']
    return r


IDX = r"(?![\s\u00a0]*[-–][\s\u00a0]*[^\W\d_])"   # "6-Enam": sayıdan hemen sonra tire+harf = liste sıra numarası


def more(text, p, sn, mx, items, flags, kind):
    """İlk ayetten sonraki  -140  ,98  ve boşlukla ayrılmış '120 140' devamlarını okur."""
    while True:
        r = re.compile(WS + r"*" + DASH + WS + r"*" + NUM + IDX).match(text, p)
        if r and items[-1][1] is None and items[-1][0] < int(r.group(1)) <= mx:
            items[-1][1] = int(r.group(1)); p = r.end(); continue
        l = re.compile(WS + r"*,[ \u00a0]*" + NUM + IDX).match(text, p)
        if l and 1 <= int(l.group(1)) <= mx and not re.compile(r"[ \u00a0]*[:/]\d").match(text, l.end()):
            items.append([int(l.group(1)), None]); p = l.end(); continue
        # "97. ve 98. ayetlerinde" · "2, 95 ve 97. ayetleri": "ve N" yalnızca N'den sonra 'ayet' sözcüğü geliyorsa listeye katılır
        c = re.compile(r"\.?" + WS + r"+ve" + WS + r"+" + NUM + r"(?=\.?" + WS + r"*[âaAÂ][yY][eEiI][tT])").match(text, p)
        if c and 1 <= int(c.group(1)) <= mx:
            items.append([int(c.group(1)), None]); p = c.end(); flags.append('liste-ve'); continue
        s = re.compile(WS + r"+" + NUM).match(text, p)  # "Bakara 120 140"
        if (kind == 'bare' and s and items[-1][1] is None and items[-1][0] < int(s.group(1)) <= mx
                and not BIRIM_RE.match(text, s.end())
                and not re.compile(r"[ \u00a0]*[:/.]\d|[ \u00a0]*\.[ \u00a0]*[âaA][yY]", re.I).match(text, s.end())):
            items[-1][1] = int(s.group(1)); p = s.end(); flags.append('bosluk-aralik'); continue
        break
    return items, p, flags


def fmt(items):
    return ', '.join(str(a) if b is None else '%d-%d' % (a, b) for a, b in items)


# ---------------------------------------------------------------- HTML izdüşümü
def project(html):
    """Etiketleri tek boşluğa çevirir; her izdüşüm karakteri için (özgün_başlangıç, özgün_bitiş) tutar."""
    proj, mp = [], []
    for m in re.finditer(r'(<[^>]+>)|(&nbsp;)|(&amp;|&#?\w+;)|([^<&]|&)', html):
        tag, nb, ent, ch = m.groups()
        if tag:
            proj.append('\x00'); mp.append((m.start(), m.end()))
        elif nb:
            proj.append('\u00a0'); mp.append((m.start(), m.end()))
        elif ent:
            proj.append('\x01'); mp.append((m.start(), m.end()))  # diğer varlıklar: harf değil, boşluk değil
        else:
            proj.append(ch); mp.append((m.start(), m.end()))
    return ''.join(proj), mp


def duzelt_html(html, names='yazildigi', surnames=None):
    """-> (yeni_html, degisenler[(eski,yeni,bayrak)], incelemeler[(metin,sebep)])"""
    proj, mp = project(html)
    # '\x00' (etiket) arama sırasında boşluk sayılır; ama eşleşme içine girerse değiştirmeyiz
    text = proj.replace('\x00', ' ')
    edits, review = [], []
    pos = 0
    while True:
        m = NAME_RE.search(text, pos)
        if not m:
            break
        name_s, name_e = m.start(1), m.end(1)
        sn = BY_KEY.get(key(m.group(1)))
        pos = name_e
        if not sn:
            continue
        mx = AYET_SAYISI[sn - 1]
        r = build_ref(text, name_e, sn, mx, None)
        if r is None:
            continue
        if r[0] == 'REVIEW':
            review.append((ctx(proj, name_s, r[1]), r[2]))
            pos = max(pos, r[1]); continue
        items, end, flags = r
        pos = max(end, name_e)
        # sade biçimde riskli ad kontrolü
        akey = key(m.group(1))
        sade = 'bare' in flags and 'ayet' not in flags and 'kal-ayet' not in flags
        if sade and BIRIM_RE.match(text, end):
            review.append((ctx(proj, name_s, end), 'sayıdan sonra birim sözcüğü var (atıf olmayabilir)'))
            continue
        if sade and akey in RISKLI_KEYS and not (baglam_ok(text, name_s, end) or kume_ok(text, name_s, end)):
            review.append((ctx(proj, name_s, end), 'sade "Ad Sayı" ve ad sıradan sözcük/özel ad olabilir'))
            continue
        if 'bosluk-aralik' in flags and (re.search(r"\d[\s\u00a0]+$", text[max(0, name_s - 8):name_s])
                                         or NAME_RE.match(text, end + len(text[end:]) - len(text[end:].lstrip()))):
            review.append((ctx(proj, name_s, end + 12), 'boşlukla ayrılmış iki sayı tablo/dizin satırı olabilir (aralık varsayılmadı)'))
            continue
        if sade and re.compile(r"[ \u00a0]+\d{1,3}(?![\d.:/])").match(text, end):
            review.append((ctx(proj, name_s, end + 6), 'sayıyla devam ediyor (tablo satırı olabilir)'))
            continue
        if sade and sn == items[0][0] and len(items) == 1 and items[0][1] is None:
            review.append((ctx(proj, name_s, end), 'sayı sûre numarasıyla aynı (sûre no mu, ayet no mu?)'))
            continue
        kalan = text[end:end + 40]
        dn = re.match(r"\.(?=[ \u00a0]+ve[ \u00a0]+)", kalan)
        if dn and NAME_RE.match(text, end + 1 + len(re.match(r"[ \u00a0]+ve[ \u00a0]+", kalan[1:]).group(0))):
            end += 1; kalan = text[end:end + 40]            # "Nisa suresinin 43. ve Maide ..." -> noktayı (sıra eki) yut
        if re.match(r"\.[ \u00a0]+[a-zçğıöşüâîû]", kalan):
            review.append((ctx(proj, name_s, end + 20), 'sıra sayısı ("97. ve 98. ayetlerinde") elle düzenlenmeli'))
            continue
        if re.match(r"[ \u00a0]+(?:ve|ile|ila|veya|ya da)[ \u00a0]+\d", kalan):
            review.append((ctx(proj, name_s, end + 20), 'atıf "ve ..." ile sürüyor (dizi yarım kalırdı)'))
            continue
        span = proj[name_s:end]
        if '\x00' in span or '\x01' in span:
            review.append((ctx(proj, name_s, end), 'atıf HTML etiketiyle bölünmüş'))
            continue
        yazim = m.group(1)
        if names == 'kanonik' and surnames:
            yazim = surnames[sn - 1]
        yeni = '%s/%s%s' % (yazim, fmt(items), ' ' if 'kal-ayet' in flags else '')
        o_s, o_e = mp[name_s][0], mp[end - 1][1]
        eski = html[o_s:o_e]
        if eski == yeni:
            continue
        edits.append((o_s, o_e, yeni, flags, ctx(proj, name_s, end)))
    out, last, chg = [], 0, []
    for s, e, yeni, fl, cx in edits:
        out.append(html[last:s]); out.append(yeni); last = e
        chg.append((html[s:e], yeni, fl, cx))
    out.append(html[last:])
    return ''.join(out), chg, review


RISKLI_KEYS = {k.replace('_', '') for k in RISKLI}


def kume_ok(text, s, e):
    """Atıf dizisinin içinde mi? (öncesinde 'N;' / 'N,' / 've' ya da sonrasında ';'/',' + başka bir sûre adı + sayı)"""
    onceki = text[max(0, s - 14):s]
    if re.search(r"[\d)’'][\s\u00a0]*[;,][\s\u00a0]*$|\bve[\s\u00a0]+$", onceki):
        return True
    if re.compile(r"['’]\w").match(text, e):          # "Hud 114'te", "İsra 78’de": sayıya ek gelmiş -> ayet no
        return True
    if re.compile(r"[\s\u00a0]+-[\s\u00a0]").match(text, e) and text[s:e].split()[0].isupper():
        return True                                          # "NUR 37 - Allah'ın ..." (büyük harfli ayet başlığı)
    m = re.compile(r"[\s\u00a0]*(?:[;,]|[\s\u00a0]ve)[\s\u00a0]*").match(text, e)
    if m:
        n2 = NAME_RE.match(text, m.end())
        if n2 and re.compile(r"[\s\u00a0/;,:(]*\d").match(text, n2.end(1)):
            return True
    return False


def baglam_ok(text, s, e):
    """Sade atıf açık bir atıf bağlamında mı? ( ... ) içinde, 'bkz.' sonrası vb."""
    onceki = text[max(0, s - 12):s]
    sonraki = text[e:e + 2]
    if re.search(r"[(\[]\s*$", onceki) or re.search(r"\b(?:bkz|bk|krş|bak|bakınız|bakiniz)\.?\s*$", onceki, re.I):
        return True
    if re.match(r"\s*[)\]]", sonraki):
        return True
    return False


def ctx(proj, s, e):
    a, b = max(0, s - 25), min(len(proj), e + 25)
    return proj[a:b].replace('\x00', ' ').replace('\x01', '&').replace('\n', ' ')


# ---------------------------------------------------------------- dosyalar
def read_ab(html):
    m = re.search(r'^const AB=(\{.*\});?$', html, re.M)
    if not m:
        sys.exit('HATA: index.html içinde AB bulunamadı')
    return m, json.loads(m.group(1))


def enc(obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    return base64.b64encode(gzip.compress(raw, 9, mtime=0)).decode('ascii')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--uygula', action='store_true', help='dosyaları gerçekten güncelle (.bak yedeği alır)')
    ap.add_argument('--isimler', choices=['yazildigi', 'kanonik'], default='yazildigi')
    ap.add_argument('--rapor', help='tam raporu bu dosyaya yaz')
    a = ap.parse_args()

    html = open(HTML, encoding='utf-8').read()
    meta = json.loads(re.search(r'^const META=(\{.*\});?$', html, re.M).group(1))
    surnames = [x['name'] for x in meta['sur']]
    rapor, ozet = [], {}

    def isle(etiket, s):
        yeni, chg, rev = duzelt_html(s, a.isimler, surnames)
        for e_, y_, fl, cx in chg:
            ek = [f for f in fl if f != 'bare']
            rapor.append('DEĞİŞİR  [%s]  %r -> %r%s  ::  ...%s...' % (etiket, e_, y_, '   (' + ','.join(ek) + ')' if ek else '', cx))
        for c_, why in rev:
            rapor.append('İNCELEME [%s]  %s  ::  ...%s...' % (etiket, why, c_))
        return yeni, chg, rev

    # --- kavramlar
    kv = json.load(open(KVJ, encoding='utf-8'))
    kv_deg = kv_inc = 0
    for it in kv:
        it['text'], chg, rev = isle('kavram:' + it['name'], it.get('text') or '')
        kv_deg += len(chg); kv_inc += len(rev)

    # --- makaleler
    m, ab = read_ab(html)
    ab_yeni, ab_deg, ab_inc = {}, 0, 0
    for aid, b64 in ab.items():
        veri = json.loads(gzip.decompress(base64.b64decode(b64)).decode('utf-8'))
        degisti = False
        for blok in veri:
            for i, x in enumerate(blok):
                if isinstance(x, str) and i > 0:
                    y, chg, rev = isle('makale:' + aid, x)
                    ab_inc += len(rev)
                    if chg:
                        blok[i] = y; ab_deg += len(chg); degisti = True
        ab_yeni[aid] = enc(veri) if degisti else b64

    print('Kavramlar : %d atıf değişecek, %d incelemeye alındı' % (kv_deg, kv_inc))
    print('Makaleler : %d atıf değişecek, %d incelemeye alındı' % (ab_deg, ab_inc))
    if a.rapor:
        open(a.rapor, 'w', encoding='utf-8').write('\n'.join(rapor) + '\n')
        print('Rapor yazıldı:', a.rapor)
    else:
        for r in rapor[:60]:
            print(r)
        if len(rapor) > 60:
            print('... (%d satır daha; tamamı için --rapor dosya.txt)' % (len(rapor) - 60))

    if not a.uygula:
        print('\nHiçbir dosya değiştirilmedi. Uygulamak için --uygula ekle.')
        return
    yedek = os.path.join(ROOT, 'tools', 'yedek')      # APK'ya girmesin diye assets dışında
    os.makedirs(yedek, exist_ok=True)
    for p in (KVJ, HTML):
        shutil.copyfile(p, os.path.join(yedek, os.path.basename(p) + '.bak'))
    json.dump(kv, open(KVJ, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    satir = 'const AB=' + json.dumps(ab_yeni, ensure_ascii=False) + ';'
    html2 = html[:m.start()] + satir + html[m.end():]
    open(HTML, 'w', encoding='utf-8').write(html2)
    print("Güncellendi (yedekler: tools/yedek/*.bak). APK'yı yeniden derle.")


if __name__ == '__main__':
    main()
