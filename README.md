# Kur'an Mealleri — Android projesi

Yenilenmiş arayüzlü uygulamanın Android (WebView) sarmalayıcısı.
9 meal, 49 makale ve yönetici paneli; internet izni yok, tamamen çevrimdışı çalışır.

## APK'yı üretmenin iki yolu

**1) Bilgisayarsız: GitHub Actions**
1. Yeni bir GitHub deposu aç, bu klasörün içindekileri yükle (`.github` klasörü dahil).
2. Depoda *Actions → APK derle → Run workflow*.
3. İş bitince sayfanın altındaki **kuran-mealleri-apk** dosyasını indir, zip'in içindeki `.apk`'yı telefona kur.

**2) Android Studio**
1. *File → Open* ile bu klasörü aç, Gradle eşitlemesini bekle.
2. *Build → Build APK(s)* ya da telefonu bağlayıp ▶ Run.

## Yönetici paneli
Ayarlar (sağ üstteki simge) → **Yönetici paneli**. İlk açılışta PIN yoktur; *Güvenlik* bölümünden PIN belirle.
Ana sayfa metin/resmi, makaleler (ekle, düzenle, sil), meal ayetleri (düzenle, eksik ayet ekle) ve kendi mealin buradan yönetilir.
Veriler yalnızca telefonda saklanır; *Yedekle / geri yükle* ile kopyalayıp başka yere kaydedebilirsin.
Uygulama verisi silinirse (Ayarlar → Uygulamalar → Depolamayı temizle) yönetici verilerin de gider; önce yedek al.

## Bilmen gerekenler
- Paket adı: `com.mb.kuran.app` (sürüm 28, yapı/kod 2). Debug ve release aynı paket adını kullanır; eski `com.mb.Kuranix` / `com.mb.Kuranix.yeni` uygulamalarından ayrı, yeni bir uygulama olarak kurulur.
- Mevcut uygulamanın üzerine güncelleme olarak kurmak için release derlemesini **orijinal anahtar dosyanla (keystore)** imzalaman gerekir. `keystore.properties` dosyası oluştur:
  ```
  storeFile=anahtar.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```
  sonra `gradle assembleRelease`. Anahtar yoksa yeni bir uygulama olarak yayınlanır.
- Eski uygulamadaki yer imleri (SQLite) bu sürüme otomatik aktarılmaz; yenisi kendi kayıtlarını tutar.
- Sistem yazı tipleri kullanılır. Google Fonts (Fraunces/Literata) istersen `AndroidManifest.xml`'e INTERNET izni ekleyip `www/index.html` içine fonts.googleapis.com bağlantısını geri koyabilirsin.
- Gerekli: güncel Android System WebView (Android 8.0+).

## Mimari not: kalıcı tercih ve geçici gezinme
- `activeSortOrder` (Mushaf/Nüzul) ve `activeAuthorId` (varsayılan meal) yalnızca `Prefs` nesnesi üzerinden, kullanıcı ayarlardan/meal seçicilerden seçtiğinde değişir (localStorage, tek doğruluk kaynağı).
- Yer imi, "Kaldığın yer", arama sonucu gibi geçici atlamalar `navigateToVerse({targetSurahId, targetVerseNumber, overrideMealId})` ile açılır; `overrideMealId` yalnızca okuma ekranının yerel durumuna (`R.tr`/`R.ov`) uygulanır ve ekran kapanınca silinir.
- Yer imi kaydı: `surahId:verseNumber` (sabit Mushaf no 1–114) + o anki meal (`overrideMealId` olarak kullanılır). Sıralama modu artık yer iminde saklanmaz.
- Varsayılan stiller: Konu Başlıkları Serif/17, Ana Sayfa metinleri Serif/16 (`DEF` sabiti); yönetici panelinden değiştirilebilir.

## Yazar Biyografileri (yönetici paneli)
- Panel → "Yazar Biyografileri": tüm yazarlar (meal yazarları + ek yazarlar) ve biyografi durumu (Var/Yok) listelenir; ekle/düzenle formunda yazar adı ve çok satırlı biyografi (## ara başlık, **kalın**, *italik*, canlı önizleme) bulunur.
- Veri `ADM.bios` altında saklanır ve yönetici yedeğine dahildir. Biyografi, meal bilgi kartında (openTrInfo) gösterilir.
- Sunucu olmadığından "API" yerel bir katmandır (`BioAPI`): `list` (GET /authors), `get` (GET /authors/:id), `create` (POST /authors), `update` (PUT /authors/:id). Oluşturma/güncelleme yalnızca yönetici paneli açıkken çalışır; silme tanımlı değildir.

## Yedekleme (dosya tabanlı)
- **Yedekle (Dosya Kaydet):** Yedek, pencere açılmadan telefonun belleğindeki `Documents/Kuran Mealleri Yedek` klasörüne kaydedilir (klasör yoksa oluşturulur); dosya adı `kuran_uygulamasi_yedek_GGAAYYYY.json`, aynı ad varsa sistem `(1)` ekler. Klasöre yazılamazsa sistemin dosya kaydetme penceresi (`ACTION_CREATE_DOCUMENT`) açılır.
- **Yedek Dosyası Yükle:** Dosya seçici (`ACTION_OPEN_DOCUMENT`) yedek klasöründe açılır; dosya doğrulanır, onay alınır ve veriler yüklenip uygulama yenilenir.
- Kapsam: tüm ayarlar (tema, yazı, seçili meal, sıralama, arama kapsamı…), yer imleri, kaldığın yer, notlar, vurgular, kayıtlı makaleler, son aramalar ve yönetici içeriği (ana sayfa, makaleler, ek mealler, biyografiler, yazı tipleri). Yönetici PIN'i yedeğe girmez.
- Android 10+ için izin gerekmez (MediaStore). Yalnızca Android 8-9'da `WRITE_EXTERNAL_STORAGE` istenir; verilmezse dosya penceresi açılır. Kotlin tarafı: `MainActivity` → `saveBackup` / `saveToBackupFolder` / `pickBackup`; JS tarafı: `buildBackup`, `parseBackup`, `applyBackup`.

## Yazar silme (yönetici paneli → Yazar Biyografileri)
- Her yazar satırında "Yazarı ve Tüm Meallerini Sil" butonu; onay penceresi: "Bu yazarı ve yazara ait tüm mealleri silmek istediğinize emin misiniz? Bu işlem geri alınamaz."
- `AuthorAPI.remove(id)` (DELETE /authors/:id eşdeğeri): önce yazarın meal kayıtları (girilen ayetler), sonra meal/yazar kaydı (ek meal silinir; yerleşik meal `ADM.hidden` ile kaldırılır), sonra biyografi, sonra yer imlerindeki meal referansları temizlenir. Hata olursa tüm değişiklik geri alınır.
- Son kalan meal silinemez; yalnızca yönetici paneli açıkken çalışır. Silinen yerleşik yazarlar yedek dosyasıyla da taşınır (`hidden`).

## Yerleşik ve dinamik mealler
- **Yerleşik (silinemez):** Diyanet İşleri, E. Hamdi Yazır, Edip Yüksel, Y. Nuri Öztürk.
- **Dinamik (yönetici panelinden silinebilir):** diğer 10 meal (`"dyn":true`) ve sonradan eklenen mealler. Yazar listesinde "Dinamik" / "Eklenen" etiketi ve "Yazarı ve Tüm Meallerini Sil" butonu vardır; yerleşik mealde buton yoktur.
- Not: dinamik meallerin verisi APK içinde durmaya devam eder; silinen meal uygulamadan tamamen kalkar (liste, okuma, arama), ancak APK boyutu küçülmez. Geri almak için yedek dosyası ya da uygulama verisini sıfırlamak gerekir.

## Makale silme
- Yönetici → Makaleler: her satırda "Makaleyi Sil"; onay: "Bu makaleyi silmek istediğinize emin misiniz?". `ArticleAPI.remove(id)` (DELETE /articles/:id eşdeğeri): düzenlemeler, makale kaydı ve kullanıcının kaydedilenler listesindeki kayıt temizlenir; hata olursa geri alınır.

## Kalıcı silme (hard delete) ve paketten arındırma
- Admin panelinden silinen **eklenen** meal/makaleler veritabanından tamamen silinir.
- Pakete gömülü (dinamik) meal ve makaleler çalışırken listeden ve okumadan kalıcı olarak kalkar. "Silinenler / Geri getir" bölümü kaldırıldı; geri getirme yolu yoktur (yalnızca yedek geri yükleme).
- **Uygulama verisi sıfırlanırsa:** APK'nın içindeki kopya yeniden okunacağından, silinen paket içeriği geri görünür. Bunu kökten çözmek için içeriğin APK'dan çıkarılması gerekir:
  1. Uygulamada Kayıtlar → Yedekle (Dosya Kaydet) ile yedek alın.
  2. `python tools/prune_deleted.py yedek.json` (ön izleme) ve `--apply` ile `index.html` içindeki meta, meal ve makale verilerinden silinenleri gerçekten çıkarın (`index.html.bak` yedeği alınır).
  3. APK'yı yeniden derleyin. Silinen içerik artık pakette yoktur; sıfırlama sonrası yalnızca kalan (aktif) varsayılan veriler yüklenir.

## Varsayılan okuma değerleri
- Okuma ayarları: yazı 16, satır aralığı 1.5, Arapça metin 14, İngilizce okunuş kapalı (kullanıcı değiştirdiyse onun seçimi korunur).
- Ana sayfa "Ey Müslüman kardeş," bloğu: 15. Okunuş ve tipografi (yönetici): yazı 14, satır aralığı 1.4.

## Makale imzaları
- Makalenin sonundaki yazar imzası (son `h` bloğu) artık başlık (`<h3>`) olarak değil, okuma yazısı boyutunda **kalın paragraf** (`p.sig`) olarak gösterilir. Ara başlıklar etkilenmez. Veri değişmedi; yalnızca `openArticle` çizimi ve `.ab p.sig` stili eklendi.

## Meal düzeltmeleri
- Öztürk: 12:87 (rahmetinden de), 11:35 (kendisi mi), 11:57 (Kuşkusuz), 2:126, 2:119, 2:236, 4:100, 43:23, 26:121, 68:1.
- Tüm meallerde: yazım hataları, bozuk karakterler, noktalama sonrası eksik boşluklar, noktalama öncesi fazla boşluklar. Hayrat'ta yanlış bölünmüş kelimelerin bir kısmı birleştirildi.
- Bilinen eksikler (veri kaynağında): Öztürk Burûc 85:15-22; Edip Yüksel 9:128-129, 22. ve 39. sûre; Diyanet Vakfı 3:99 kesik; Öztürk 2:280 kesik görünüyor; Bayraklı'da 3:33, 18:40, 51:15, 56:13, 82:7 çift kayıt.

## Yazar fotoğrafları (yönetici paneli → Yazar Biyografileri)
- Biyografi formunda **Fotoğraf seç / Kaldır**: resim kare kırpılıp 360 px, JPEG olarak küçültülür (yaklaşık 20-40 KB) ve `ADM.bios[id].photo` altında saklanır. Meal bilgi kartında biyografinin üstünde yuvarlak görünür. Yedeğe dahildir ve yedekten geri yüklenir.
- **Kalıcılık:** panelden eklenen fotoğraf uygulama verisindedir; "Depolamayı temizle" onu da siler (yedekten geri gelir). Silinmemesi için fotoğrafları APK'ya göm:
  1. Fotoğrafları ekle → Kayıtlar → Yedekle (Dosya Kaydet).
  2. `python tools/bake_photos.py yedek.json` (ön izleme), sonra `--apply`.
  3. APK'yı yeniden derle. `index.html` içindeki `PHOTOS` sabitine gömülen fotoğraflar sıfırlama sonrası da görünür.
- Gömülü fotoğraflar (11 yazar) projeye zaten işlendi; yenisini eklemek için yukarıdaki adımları tekrarla.
- Suat Yıldırım meali ve biyografisi paketten kalıcı olarak çıkarıldı (`prune_deleted.py`).
- Y. Nuri Öztürk mealinde 32 ayet (40:5, 39:57, 41:39, 42:10, 41:45, 42:14, 42:38, 43:12, 42:31, 42:18, 43:18, 43:17, 43:33, 43:13, 43:48, 44:20, 43:69, 45:21, 46:6, 45:14, 45:4, 18:60, 18:78, 18:106, 18:93, 18:56, 18:54, 16:80, 16:99, 71:13, 16:55, 16:102) kullanıcı metniyle değiştirildi; eski/yeni metinler `tools/ynuri_ayet_degisiklikleri.json` içinde.
- Öncelik: panelden eklenen fotoğraf > APK'ya gömülü fotoğraf. Panelde "Kaldır" yalnızca panel fotoğrafını siler; gömülü olan görünmeye devam eder.

## Makale paragraf girintisi
- Makale gövdesindeki paragrafların ilk satırı resmî/akademik yazım düzenine uygun olarak 1,5 em içeriden başlar (`.ab.ab-j p` için `text-indent:1.5em`). Makale metni (veri) değişmedi; yalnızca `openArticle` çizimi (`artP`) ve CSS eklendi.
- Girintisiz kalanlar: kalın (`<b>`) ara başlık satırları, yazar imzası (`p.sig`), madde işaretli satırlar (`p.li`), ayet alıntıları (`blockquote`) ve resimler.
- Bir blokta `<br>` ile ayrılmış satırlar varsa kısa ilk satır (≤100 karakter) başlık sayılıp girintisiz bırakılır, sonraki satırlar girintili çizilir. Girinti değerini değiştirmek için CSS'teki `1.5em` değerini düzenleyin.
- Madde işaretli satırlarda (`p.li`) işaret (•) sabit genişlikli (`1.1em`) satır içi blok yapıldı; böylece iki yana yaslı metinde ilk satırın boşlukları işaretin ardındaki aralığı açmaz ve tüm maddeler soldan aynı hizadan başlar.
- Yeni makale eklendi: "Salat" (Genel Konular; `SALAT.docx` kaynağından). Aynı girinti, iki yana yaslama ve otomatik tireleme kuralları uygulanır; gövde yazısı olduğu gibi aktarıldı, yalnızca boşluk fazlalıkları temizlendi ve tireyle başlayan 4 satır madde (`li`) olarak biçimlendi.

- Hakkı Yılmaz meali (`hyilmaz`): `tr.json` kaynaklı, pasaj (necm) bazlı. Dipnotlar ve tebyîn metinleri dahil (tebyîn sûre sûre ayrı yüklenir), kavram kutuları dahil değil. Bölünmüş pasajlar iki karta ayrılır (ikincisinde aynı metin tekrar eder).
- Ayet altında `Dipnot` (solda) ve `Tebyin` (sağda) düğmeleri (`.vact` / `.vbtn`), her biri ayrı alt pencerede (`openSheet`) açılır; dipnot kartta `c[3]`, tebyin kartta `c[4]=1` bayrağı + `TBD` sözlüğünde saklanır.

## Kavramlar modülü

- Veri: `app/src/main/assets/www/kavramlar.json` (430 kavram; `name`, `name_ar`, `name_search`, `text` (HTML), `sira`). Açılışta değil, ilk kez "Kavramlar" açılınca yüklenir.
- Ana menü: Kur'an Mealleri ile Makaleler Konular arasındaki "Kavramlar" satırı.
- Liste: Türkçe alfabe sırası, harf başlıkları, sağda harf çubuğu (dokun/kaydır), ad + metinde arama.
- Detay: metin içindeki sûre/ayet atıfları (`Bakara/255`, `Bakara 2:255`, `Nisâ; 58, 135`, `Bakara sûresinin 196. ayeti`, `Bakara sûresi` …) otomatik bulunur (`kvLinkHtml`), vurgulanır ve dokununca ayet ekranı açılır. Ayet numarası sûrenin ayet sayısını aşıyorsa bağlantı kurulmaz (yanlış eşleşmeleri önler).
- Geri dönüş: Kavramlar katmanları ayet ekranının altında açık kalır; ayet ekranından çıkınca aynı kavram ve aynı kaydırma konumu görünür. Bu yoldan açılan ayet ekranı "Kaldığın yer" kaydını değiştirmez.
