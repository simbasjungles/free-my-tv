/* Samsung ECK TV home. Deliberately no autoplay video or GPU blur on the 2018 TV generation. */
(function () {
  'use strict';
  if (!window.CONFIG || !CONFIG.eckHome) return;
  var root, content, rail, appsBox, heroPhoto, heroTitle, heroMeta, heroText, heading, count, actions, status;
  var state = ECK.read('position', {}), row = 1, col = 0, selected = 0, appPage = 0, apps = [], films = [], view = 'movies';
  var modal = null, modalIndex = 0, menuRows = [], form = null, toastTimer = 0, idleAt = Date.now();
  var saver = null, saverTimer = 0, sceneIndex = 0, saverGeneration = 0, heroGeneration = 0, trailerGeneration = 0;
  var pool = ECK.imagePool(4), scenes = [], sceneBusy = false, heroTimer = 0, lastHero = '', lastRefresh = 0, lastWeatherRefresh = Date.now();
  var sceneFeed = 'https://raw.githubusercontent.com/celilkamis-arch/ECK-TV/main/web/eck-scenes.json';
  var nextScenesRefresh = Date.now() + 86400000, lastTickMinute = '';
  var demo = TV.name === 'browser' && /(?:[?&])demo=1(?:&|$)/.test(location.search);
  function el(tag, cls, parent, text) {
    var n = document.createElement(tag); if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text; if (parent) parent.appendChild(n); return n;
  }
  function setClass(n, name, on) {
    var list = n.className.split(/\s+/).filter(function (s) { return s && s !== name; });
    if (on) list.push(name); n.className = list.join(' ');
  }
  function button(parent, cls, text, fn) {
    var b = el('button', cls, parent, text); b.type = 'button'; b.onclick = fn; return b;
  }
  function toast(text) {
    var old = root.querySelector('.eck-toast'); if (old) root.removeChild(old);
    var n = el('div', 'eck-toast', root, text); n.setAttribute('role', 'status');
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { if (n.parentNode) n.parentNode.removeChild(n); }, 6000);
  }
  function fit() {
    var scale = TV.name === 'browser' ? Math.min(innerWidth / 1920, innerHeight / 1080) : innerWidth / 1920;
    root.style.webkitTransform = root.style.transform = 'scale(' + scale + ')';
    root.style.left = TV.name === 'browser' ? Math.max(0, (innerWidth - 1920 * scale) / 2) + 'px' : '0';
  }
  function savePosition() {
    ECK.write('position', {row: row, col: col, selected: films[selected] ? films[selected].id : null,
      index: selected, view: view, appPage: appPage});
  }
  function restorePosition() {
    var p = ECK.read('position', {});
    row = [0, 1, 2, 3, 5].indexOf(p.row) >= 0 ? p.row : 5;
    col = Math.max(0, +p.col || 0);
    selected = Math.max(0, +p.index || 0); appPage = Math.max(0, +p.appPage || 0);
    view = p.view === 'favourites' ? 'favourites' : 'movies';
    state = p;
  }
  function selectedMovie() { return films[selected] || null; }
  function build() {
    document.body.className = 'eck'; document.title = 'ECK TV';
    root = el('main', '', document.body); root.id = 'eck'; root.setAttribute('aria-label', 'ECK TV ana ekran');
    el('div', 'eck-wall', root); el('div', 'eck-wash', root);
    var header = el('header', 'eck-header', root), brand = el('div', 'eck-brand', header, 'ECK ');
    el('b', '', brand, 'TV'); el('div', 'eck-tag', header, 'SENİN EKRANIN');
    status = el('div', 'eck-status', header);
    var top = el('nav', 'eck-top', header);
    [['▣', 'Canlı TV'], ['✦', 'Ambiyans'], ['⚙', 'Ayarlar']].forEach(function (x, i) {
      var b = button(top, i === 1 ? 'eck-art' : '', x[0], function () { row = 0; col = i; activate(); });
      b.setAttribute('aria-label', x[1]); el('span', '', b, x[1]); b.setAttribute('data-focus', '0:' + i);
    });
    var viewport = el('div', 'eck-viewport', root); content = el('div', 'eck-content', viewport);
    var hero = button(content, 'eck-hero', '', function () { row = 5; col = 0; focus(); activate(); });
    hero.setAttribute('data-focus', '5:0'); hero.setAttribute('aria-label', 'Öne çıkan film');
    heroPhoto = el('div', 'eck-hero-photo', hero); el('div', 'eck-hero-shade', hero);
    var copy = el('div', 'eck-hero-copy', hero); el('div', 'eck-eyebrow', copy, 'BU AKŞAM NE İZLESEK?');
    heroTitle = el('h1', '', copy, 'Yeni bir hikâye keşfet.'); heroMeta = el('div', 'eck-meta', copy);
    heroText = el('p', 'eck-description', copy);
    el('div', 'eck-hero-note', hero, 'Filmde OK → YouTube fragmanı');
    heading = el('h2', 'eck-heading', content); count = el('div', 'eck-count', content);
    rail = el('div', 'eck-rail', content); actions = el('div', 'eck-actions', content);
    el('h2', 'eck-app-heading', content, 'Uygulamaların'); appsBox = el('div', 'eck-apps', content);
    el('div', 'eck-footer', root, '↑ ↓ Bölümler   ·   ← → Seç   ·   OK Aç   ·   Geri Dön');
    el('div', 'eck-footer-right', root, demo ? 'ÖNİZLEME · ÖRNEK İÇERİK' : 'ECK TV 1.7');
  }
  function clockText() { var d = new Date(); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }
  function weatherText() { return Data.weather ? Data.degrees(Data.weather.temp) + ' · ' + Data.condition(Data.weather.code, Data.weather.isDay) : 'Hava durumu bekleniyor'; }
  function tick() {
    if (document.hidden) return;
    root.className = Data.isNight(Date.now()) ? 'night' : '';
    document.body.style.background = root.className === 'night' ? '#080c18' : '#eceef4';
    status.textContent = clockText(); el('small', '', status, weatherText());
    if (saver) {
      var clock = saver.querySelector('.eck-saver-clock'); clock.firstChild.nodeValue = clockText();
      clock.querySelector('.eck-saver-date').textContent = formatDate('format|EEEE, MMMM d', Date.now());
      saver.querySelector('.eck-saver-weather').firstChild.nodeValue = weatherText();
    }
  }
  function demoFilms() {
    return [
      {id: 157336, title: 'Yıldızlararası', original: 'Interstellar', year: '2014', rating: 8.4,
        overview: 'Bir grup kâşif, insanlığın geleceği için yıldızların ötesine uzanan bir yolculuğa çıkar.', backdrop: '/xJHokMbljvjADYdit5fK5VQsXEG.jpg'},
      {id: 27205, title: 'Başlangıç', original: 'Inception', year: '2010', rating: 8.4,
        overview: 'Rüyalar ve gerçeklik arasındaki sınırlar silinirken, tek bir fikir her şeyi değiştirebilir.', backdrop: '/s3TBrRGB1iav7gFOCNx3H31MoES.jpg'}
    ];
  }
  function updateFilms() {
    var current = selectedMovie(), wanted = current ? current.id : state.selected;
    films = view === 'favourites' ? ECK.favourites() : demo ? demoFilms().filter(function (m) { return ECK.read('hidden', []).indexOf(m.id) < 0; }) : ECK.list();
    var match = -1; for (var i = 0; i < films.length; i++) if (films[i].id === wanted) match = i;
    selected = match >= 0 ? match : Math.min(selected, Math.max(0, films.length - 1));
    renderFilms(); focus();
  }
  function renderFilms() {
    rail.innerHTML = ''; actions.innerHTML = '';
    heading.textContent = view === 'favourites' ? 'Favorilerin' : ECK.mode() === 'genres' ? 'Seçtiğin türlerden' : 'Türkiye’de popüler';
    count.textContent = films.length ? (selected + 1) + ' / ' + films.length : '';
    var first = Math.floor(selected / 6) * 6;
    films.slice(first, first + 6).forEach(function (m, i) {
      var b = button(rail, 'eck-film', '', function () { selected = first + i; row = 1; renderFilms(); focus(); play(false); });
      b.style.left = (i * 297 + 5) + 'px'; b.style.backgroundImage = 'url("' + Data.image(m.backdrop, 'w342') + '")';
      b.setAttribute('data-focus', '1:' + (first + i)); b.setAttribute('aria-label', m.title);
      el('span', '', b, m.title);
    });
    var m = selectedMovie();
    [[m && ECK.isFavourite(m.id) ? '★ Favoriden çıkar' : '☆ Favoriye ekle', function () { if (selectedMovie()) ECK.toggleFavourite(selectedMovie()); }],
      ['İlgilenmiyorum', function () { if (selectedMovie()) ECK.hide(selectedMovie().id); }],
      ['YouTube’da ara', function () { play(true); }],
      [view === 'favourites' ? 'Tüm öneriler' : 'Favorilerim', function () { view = view === 'favourites' ? 'movies' : 'favourites'; selected = 0; state = {}; updateFilms(); }]
    ].forEach(function (a, i) {
      var b = button(actions, 'eck-chip', a[0], function () { row = 2; col = i; a[1](); focus(); }); b.setAttribute('data-focus', '2:' + i);
      b.disabled = !m && i < 3;
    });
    updateHero(m);
  }
  function updateHero(m) {
    heroTitle.textContent = m ? m.title : !Prefs.tmdbKey() ? 'Film önerilerini etkinleştir' : 'Yeni bir hikâye keşfet.';
    heroMeta.textContent = m ? m.year + '  ·  ★ ' + m.rating.toFixed(1) + '  ·  Film' : 'Şehirler, yıldızlar ve sana ait bir ekran.';
    heroText.textContent = m ? m.overview || 'Fragmanı açmak için film kartında OK tuşuna bas.'
      : view === 'favourites' ? 'Henüz favorin yok. OK ile tüm önerilere dön.' : ECK.error() || (ECK.busy() ? 'Filmler yükleniyor…' : 'Bu listede gösterilecek film yok. OK ile önerileri yenile.');
    root.querySelector('.eck-hero').setAttribute('aria-label', m ? m.title + ' · YouTube fragmanını aç' : Prefs.tmdbKey() ? 'Önerileri yenile' : 'Film önerilerini etkinleştir');
    root.querySelector('.eck-hero-note').textContent = m ? 'OK · YouTube’da fragmanı aç' : Prefs.tmdbKey() ? 'OK · Önerileri yenile' : 'OK · TMDB anahtarını ekle';
    var url = m ? Data.image(m.backdrop, 'w1280') : '';
    if (lastHero === url) return; lastHero = url; var mine = ++heroGeneration;
    clearTimeout(heroTimer);
    if (!url) { heroPhoto.className = 'eck-hero-photo'; heroPhoto.style.backgroundImage = ''; return; }
    // Fast repeated remote presses do not decode a new 1280px photo for every intermediate card.
    heroTimer = setTimeout(function () {
      pool.load(url, function (ok) {
        if (!ok || mine !== heroGeneration || document.hidden) return;
        heroPhoto.style.backgroundImage = 'url("' + url + '")'; heroPhoto.className = 'eck-hero-photo ready';
      });
    }, 180);
  }
  function renderApps() {
    appsBox.innerHTML = ''; var pages = Math.max(1, Math.ceil(apps.length / 8)); appPage = Math.min(appPage, pages - 1);
    apps.slice(appPage * 8, appPage * 8 + 8).forEach(function (a, i) {
      var b = button(appsBox, 'eck-app app-' + a.key + (a.icon ? '' : ' no-icon') + (a.available === false ? ' unavailable' : ''), '', function () { row = 3; col = i; focus(); launchApp(a); });
      b.style.left = (i * 220 + 5) + 'px'; b.style.top = '5px';
      b.setAttribute('data-focus', '3:' + i);
      b.setAttribute('aria-label', a.title);
      if (a.icon) b.style.backgroundColor = a.color || '#202a42';
      if (a.icon) { var img = el('img', '', b); img.src = a.icon; img.alt = ''; img.onerror = function () { b.className += ' no-icon'; img.style.display = 'none'; }; }
      el('span', '', b, a.title);
      if (a.available === false) el('small', 'eck-unavailable', b, 'Yüklü değil');
    });
    if (!apps.length) el('p', '', appsBox, 'Uygulamalar yükleniyor…');
    if (pages > 1) el('div', 'eck-app-page', appsBox, 'Sayfa ' + (appPage + 1) + ' / ' + pages + ' · satır sonunda →');
  }
  function columns(r) {
    if (r === 0) return 3; if (r === 5) return 1;
    if (r === 1) return films.length; if (r === 2) return 4;
    return r === 3 ? Math.min(8, Math.max(0, apps.length - appPage * 8)) : 0;
  }
  function focus() {
    var nodes = root.querySelectorAll('.focused'); for (var i = 0; i < nodes.length; i++) setClass(nodes[i], 'focused', false);
    if (modal) { focusModal(); return; }
    if (row === 1) col = selected;
    col = Math.max(0, Math.min(col, Math.max(0, columns(row) - 1)));
    if (row === 2 && !films.length) col = 3;
    var node = root.querySelector('[data-focus="' + row + ':' + col + '"]'); if (node) setClass(node, 'focused', true);
    content.style.webkitTransform = content.style.transform = 'translateY(0)';
    savePosition();
  }
  function move(dx, dy) {
    idleAt = Date.now();
    if (modal) {
      if (form) {
        var input = modal.querySelector('input'); if (document.activeElement === input) return;
        modalIndex = Math.max(0, Math.min(form.length - 1, modalIndex + (dy || dx))); focusModal(); return;
      }
      modalIndex = Math.max(0, Math.min(menuRows.length - 1, modalIndex + (dy || dx))); focusModal(); return;
    }
    if (dy) {
      var order = [0, 5, 3, 1, 2], next = order.indexOf(row) + dy;
      while (next >= 0 && next < order.length && !columns(order[next])) next += dy;
      if (next >= 0 && next < order.length) { row = order[next]; col = row === 1 ? selected : Math.min(col, columns(row) - 1); }
    } else if ((row === 1 || row === 5) && films.length) {
      selected = Math.max(0, Math.min(films.length - 1, selected + dx)); renderFilms();
    } else if (row === 3 && dx && (col + dx < 0 || col + dx >= columns(row))) {
      var pages = Math.ceil(apps.length / 8), nextPage = appPage + (dx > 0 ? 1 : -1);
      if (nextPage >= 0 && nextPage < pages) { appPage = nextPage; col = dx > 0 ? 0 : 7; renderApps(); }
    } else col = Math.max(0, Math.min(columns(row) - 1, col + dx));
    focus();
  }
  function launchApp(a) {
    if (!a) return;
    if (a.available === false || !a.id) { toast(a.title + ' TV’de yüklü değil. Samsung Apps üzerinden yükledikten sonra ECK TV’yi tekrar aç.'); return; }
    savePosition();
    TV.launch(a.id, null, function (ok) { if (!ok || TV.name === 'browser') toast(TV.name === 'browser' ? 'Önizleme: ' + a.title : 'Uygulama açılamadı.'); });
  }
  function play(searchOnly) {
    var m = selectedMovie(); if (!m) return;
    var youtube = apps.filter(function (a) { return /youtube/i.test(a.title) && !/kids/i.test(a.title); })[0];
    if (!youtube || !youtube.id) { toast('YouTube uygulaması bulunamadı.'); return; }
    savePosition(); var mine = ++trailerGeneration; toast(searchOnly ? 'YouTube araması hazırlanıyor…' : 'Türkçe fragman aranıyor…');
    function open(video, query) {
      if (mine !== trailerGeneration || document.hidden) return;
      TV.youtube(youtube.id, video, query, function (ok) {
        if (!ok) toast(TV.name === 'browser' ? 'Önizleme: ' + query : 'YouTube bağlantıyı açamadı. “YouTube’da ara” seçeneğini deneyebilirsin.');
        else toast('YouTube’a yönlendirildi. Yanlış ekran açılırsa dönüp “YouTube’da ara” seç.');
      });
    }
    if (searchOnly || demo) open('', ECK.searchQuery(m)); else ECK.trailer(m, open);
  }
  function activate() {
    idleAt = Date.now();
    if (modal) {
      if (form) { if (form[modalIndex]) form[modalIndex].click(); }
      else if (menuRows[modalIndex]) menuRows[modalIndex].run();
      return;
    }
    if (row === 0) {
      if (col === 0) { savePosition(); TV.launch('source:TV:1', null, function (ok) { if (!ok || TV.name === 'browser') toast(TV.name === 'browser' ? 'Önizleme: TV’nin son uydu yayını açılır.' : 'TV yayını açılamadı. Samsung Kaynak menüsünden TV’yi kontrol et.'); }); }
      if (col === 1) showSaver(); if (col === 2) settings();
    } else if (row === 1) play(false);
    else if (row === 2) { var b = actions.children[col]; if (b) b.click(); }
    else if (row === 5) {
      if (selectedMovie()) play(false);
      else if (view === 'favourites') { view = 'movies'; updateFilms(); }
      else if (!Prefs.tmdbKey()) keyForm();
      else ECK.refresh(true);
    } else launchApp(apps[appPage * 8 + col]);
  }
  function closeModal() { if (modal) root.removeChild(modal); modal = null; form = null; menuRows = []; idleAt = Date.now(); focus(); }
  function openModal(title, note) {
    if (modal) root.removeChild(modal);
    modal = el('section', 'eck-modal', root); modal.setAttribute('role', 'dialog'); modal.setAttribute('aria-label', title);
    var box = el('div', 'eck-modal-box', modal); el('h2', '', box, title); el('p', '', box, note);
    el('div', 'eck-dialog-note', box, '↑ ↓ Seç   ·   OK Uygula   ·   Geri Kapat'); modalIndex = 0; form = null; return box;
  }
  function menu(title, note, rows) {
    var box = openModal(title, note), viewport = el('div', 'eck-menu-window', box), list = el('div', 'eck-menu', viewport);
    menuRows = rows;
    rows.forEach(function (r, i) {
      var b = button(list, 'eck-menu-row', r.label, function () { modalIndex = i; r.run(); });
      if (r.value) el('em', '', b, r.value);
    }); focusModal();
  }
  function focusModal() {
    if (!modal) return;
    var nodes = form || modal.querySelectorAll('.eck-menu-row');
    for (var i = 0; i < nodes.length; i++) setClass(nodes[i], 'focused', i === modalIndex);
    var list = modal.querySelector('.eck-menu');
    if (list) list.style.webkitTransform = list.style.transform = 'translateY(-' + Math.max(0, modalIndex - 8) * 69 + 'px)';
  }
  function settings() {
    menu('Ekran ayarları', 'Tercihlerin yalnızca bu TV’de saklanır.', [
      {label: 'Film önerileri', value: ECK.mode() === 'popular' ? 'Türkiye’de popüler' : 'Seçtiğim türler', run: modes},
      {label: 'Türlerimi seç', value: ECK.selectedGenres().length + ' tür', run: genreMenu},
      {label: 'Favorilerim', value: ECK.favourites().length + ' film', run: function () { view = 'favourites'; selected = 0; closeModal(); updateFilms(); }},
      {label: 'TMDB anahtarı', value: Prefs.tmdbKey() ? 'Kayıtlı' : 'Ekle', run: keyForm},
      {label: 'Hava durumu şehri', value: Prefs.city() || 'Seç', run: cityForm},
      {label: 'Tema', value: Prefs.theme() === 'dark' ? 'Koyu' : Prefs.theme() === 'light' ? 'Açık' : 'Gündüz / gece', run: themeMenu},
      {label: 'Ekran koruyucuyu önizle', value: 'Şehir / uzay', run: function () { closeModal(); showSaver(); }},
      {label: 'Önerileri yenile', run: function () { closeModal(); ECK.refresh(true); }},
      {label: 'Gizlenen filmleri tekrar göster', run: function () { ECK.resetHidden(); toast('Gizlenen filmler tekrar gösteriliyor.'); }},
      {label: 'Hakkında', value: 'ECK TV 1.7', run: about}
    ]);
  }
  function modes() {
    menu('Film önerileri', 'Platform kısıtlaması yok. Seçtiğin liste ana ekranda gösterilir.', [
      {label: 'Türkiye’de popüler', value: ECK.mode() === 'popular' ? '✓' : '', run: function () { view = 'movies'; selected = 0; state = {}; ECK.setMode('popular'); settings(); }},
      {label: 'Seçtiğim türler', value: ECK.mode() === 'genres' ? '✓' : '', run: function () { view = 'movies'; selected = 0; state = {}; ECK.setMode('genres'); settings(); }}
    ]);
  }
  function genreMenu(keep) {
    var at = keep === true ? modalIndex : 0;
    menu('Türlerim', 'Seçimler anında kaydedilir ve film araması otomatik başlar. Geri ile ana ekrana dön.', ECK.genres.map(function (g) {
      return {label: g[1], value: ECK.selectedGenres().indexOf(g[0]) >= 0 ? '✓' : '', run: function () { view = 'movies'; ECK.toggleGenre(g[0]); genreMenu(true); }};
    })); modalIndex = at; focusModal();
  }
  function themeMenu() {
    menu('Tema', 'Otomatik tema, hava durumundaki gün doğumu ve batımını kullanır.', [
      {label: 'Gündüz açık / gece koyu', run: function () { Prefs.set('theme', 'auto'); tick(); settings(); }},
      {label: 'Her zaman koyu', run: function () { Prefs.set('theme', 'dark'); tick(); settings(); }},
      {label: 'Her zaman açık', run: function () { Prefs.set('theme', 'light'); tick(); settings(); }}
    ]);
  }
  function inputForm(title, note, type, submit) {
    var box = openModal(title, note), input = el('input', '', box); input.type = type; input.autocomplete = 'off';
    input.setAttribute('aria-label', title);
    var bar = el('div', 'eck-form-actions', box);
    var apply = button(bar, 'eck-chip', 'Kaydet / ara', function () { input.blur(); submit(input.value, box); });
    var cancel = button(bar, 'eck-chip', 'İptal', settings);
    form = [input, apply, cancel]; input.onclick = function () { input.focus(); modalIndex = 0; };
    input.focus(); focusModal();
  }
  function keyForm() {
    inputForm('TMDB anahtarı', 'themoviedb.org → Ayarlar → API. Anahtar sadece TV’ye kaydedilir; GitHub’a gönderilmez.', 'password', function (value) {
      var ownModal = modal; if (!value.trim()) { toast('Anahtar boş olamaz.'); return; }
      toast('Anahtar kontrol ediliyor…');
      Data.checkKey(value.trim(), function (err) {
        if (modal !== ownModal) return;
        if (err) { toast('Anahtar doğrulanamadı. Bağlantıyı ve anahtarı kontrol et.'); return; }
        Prefs.set('tmdbKey', value.trim()); ECK.refresh(true); settings(); toast('Anahtar TV’ye kaydedildi.');
      });
    });
  }
  function cityForm() {
    inputForm('Hava durumu şehri', 'Şehir adını yaz, ardından listeden doğru konumu seç.', 'text', function (value) {
      var ownModal = modal; if (!value.trim()) return;
      toast('Şehir aranıyor…');
      Data.findCity(value.trim(), function (err, found) {
        if (modal !== ownModal) return;
        if (err || !found || !found.length) { toast('Şehir bulunamadı. Tekrar deneyebilirsin.'); return; }
        menu('Şehrini seç', 'Hava durumu için konum seçimi', found.map(function (p) {
          return {label: p.label, run: function () { Prefs.setCity(p.city, p.lat, p.lon); Data.cityChanged(); settings(); tick(); }};
        }));
      });
    });
  }
  function about() {
    menu('ECK TV 1.7', 'Free My TV temel alınarak geliştirildi. Film bilgileri TMDB’den sağlanır; TMDB tarafından onaylanmış değildir.', [
      {label: 'Görseller', value: 'Unsplash / NASA', run: function () { toast('Şehir: Unsplash · Uzay: NASA Images. Kaynak adı ambiyans ekranında görünür.'); }},
      {label: 'Kumanda', value: 'Yönler · OK · Geri', run: function () {}},
      {label: 'Geri', run: settings}
    ]);
  }
  function validScenes(list) {
    return (list instanceof Array ? list : []).filter(function (s) {
      return s && typeof s.url === 'string' && /^https:\/\/(images\.unsplash\.com|images-assets\.nasa\.gov)\//.test(s.url);
    }).slice(0, 12).map(function (s) { return {url: s.url, title: String(s.title || 'Şehir ve uzay').slice(0, 100), source: String(s.source || '').slice(0, 40)}; });
  }
  function loadScenes() {
    var cached = ECK.read('scenes', null);
    if (cached && cached.list) scenes = validScenes(cached.list);
    function apply(list) {
      var good = validScenes(list); if (!good.length) return;
      scenes = good; ECK.write('scenes', {at: Date.now(), list: good}); warmScene();
    }
    function online() {
      if (sceneBusy || document.hidden) return; sceneBusy = true;
      ECK.request(sceneFeed, {}, function (err, data) {
        sceneBusy = false; if (!err && data) apply(data.scenes);
        // NASA's public image search needs no account or key. Keep only six thumbnails and their credit.
        ECK.request('https://images-api.nasa.gov/search?q=nebula&media_type=image&page_size=6', {}, function (e, body) {
          if (e || !body || !body.collection || document.hidden) return;
          var items = body.collection.items || [], space = [];
          items.forEach(function (item) {
            var links = item.links || [], info = item.data && item.data[0];
            if (links[0] && info) space.push({url: links[0].href, title: info.title, source: 'NASA'});
          });
          if (space.length) apply(scenes.filter(function (s) { return s.source !== 'NASA'; }).slice(0, 6).concat(space));
        });
      });
    }
    ECK.request('eck-scenes.json', {}, function (err, data) {
      if (!scenes.length && !err && data) apply(data.scenes);
      else warmScene();
      if (!cached || Date.now() - cached.at > 86400000 || Date.now() < cached.at) online();
    });
  }
  function warmScene() {
    if (!scenes.length || document.hidden) return;
    var scene = scenes[sceneIndex % scenes.length];
    pool.load(scene.url, function (ok) {
      if (!ok || document.hidden) return;
      root.querySelector('.eck-wall').style.backgroundImage = 'url("' + scene.url + '")';
      if (!selectedMovie()) { heroPhoto.style.backgroundImage = 'url("' + scene.url + '")'; heroPhoto.className = 'eck-hero-photo ready'; }
    });
  }
  function showSaver() {
    if (saver || document.hidden) return;
    sceneIndex = ECK.sceneStart(scenes, ECK.read('lastSceneStart', ECK.read('lastScene', '')));
    if (scenes.length) ECK.write('lastSceneStart', scenes[sceneIndex].url);
    savePosition(); saver = el('section', 'eck-saver', root); saver.setAttribute('aria-label', 'Ekran koruyucu');
    var a = el('div', 'eck-saver-photo', saver), b = el('div', 'eck-saver-photo', saver);
    el('div', 'eck-saver-shade', saver);
    var clock = el('div', 'eck-saver-clock', saver, clockText());
    el('span', 'eck-saver-date', clock, formatDate('format|EEEE, MMMM d', Date.now()));
    var w = el('div', 'eck-saver-weather', saver, weatherText()); el('small', '', w, Prefs.city() || '');
    var credit = el('div', 'eck-saver-credit', saver, 'Şehir ve uzay · Görseller yükleniyor…'), front = b;
    var mine = ++saverGeneration;
    function next(attempts) {
      if (!saver || mine !== saverGeneration || document.hidden) return;
      if (!scenes.length) { credit.textContent = 'Çevrimdışı ambiyans · Görseller için internet bağlantısını kontrol et.'; saverTimer = setTimeout(function () { next(0); }, 45000); return; }
      var scene = scenes[sceneIndex++ % scenes.length];
      pool.load(scene.url, function (ok) {
        if (!saver || mine !== saverGeneration || document.hidden) return;
        if (!ok && attempts < Math.min(scenes.length - 1, 3)) { next(attempts + 1); return; }
        if (ok) {
          var back = front === a ? b : a; back.style.backgroundImage = 'url("' + scene.url + '")';
          setClass(back, 'on', true); setClass(front, 'on', false); front = back;
          credit.textContent = scene.title + ' · ' + scene.source;
          ECK.write('lastScene', scene.url);
          var following = scenes[sceneIndex % scenes.length]; pool.load(following.url, function () {});
        } else credit.textContent = 'Bağlantı bekleniyor · Son görüntü korunuyor';
        saverTimer = setTimeout(function () { next(0); }, 45000);
      });
    }
    next(0);
  }
  function hideSaver() {
    saverGeneration++; clearTimeout(saverTimer);
    if (saver) root.removeChild(saver); saver = null; idleAt = Date.now(); focus();
  }
  function back() {
    if (modal) {
      closeModal(); return;
    }
    if (row !== 0) { row = 0; col = 0; focus(); } else { savePosition(); TV.back(); }
  }
  function key(e) {
    var k = e.keyCode; idleAt = Date.now();
    if (saver) { hideSaver(); e.preventDefault(); e.stopPropagation(); return; }
    if (form && document.activeElement === form[0]) {
      if (k === 13 || k === 65376) { form[0].blur(); modalIndex = 1; focusModal(); e.preventDefault(); }
      else if (k === 10009 || k === 27 || k === 65385) { form[0].blur(); modalIndex = 1; focusModal(); e.preventDefault(); }
      return;
    }
    if (k === 37) move(-1, 0); else if (k === 39) move(1, 0);
    else if (k === 38) move(0, -1); else if (k === 40) move(0, 1);
    else if (k === 13) activate(); else if (k === TV.keys.back || k === 27 || k === 8) back(); else return;
    e.preventDefault();
  }
  function reloadApps() {
    TV.apps(function (list) {
      var hide = (CONFIG.hideApps || []).map(function (s) { return s.toLowerCase(); });
      apps = list.filter(function (a) { return !hide.some(function (s) { return (a.id + ' ' + a.title).toLowerCase().indexOf(s) >= 0; }); });
      apps = ECK.launcherApps(apps);
      renderApps(); focus();
    });
  }
  function resume() {
    hideSaver(); restorePosition(); updateFilms(); reloadApps(); tick();
    idleAt = Date.now(); Data.refresh();
    if (!demo && Date.now() - lastRefresh > 6 * 3600000) { lastRefresh = Date.now(); ECK.refresh(false); }
  }
  restorePosition(); build(); fit(); I18N.set('tr'); I18N.setClock('24');
  ECK.onChange(updateFilms); Data.onChange(tick); reloadApps(); updateFilms(); tick(); loadScenes();
  Data.refresh(); if (!demo) { ECK.refresh(false); lastRefresh = Date.now(); }
  document.addEventListener('keydown', key); window.addEventListener('resize', fit);
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { savePosition(); hideSaver(); clearTimeout(heroTimer); trailerGeneration++; return; }
    lastHero = ''; resume();
  });
  window.addEventListener('appcontrol', resume);
  setInterval(function () {
    if (document.hidden) return;
    if (clockText() !== lastTickMinute) { lastTickMinute = clockText(); tick(); }
    if (Date.now() - lastWeatherRefresh >= 60000) { lastWeatherRefresh = Date.now(); Data.refresh(); }
    if (Date.now() >= nextScenesRefresh) { nextScenesRefresh = Date.now() + 86400000; loadScenes(); }
    if (!modal && !saver && Date.now() - idleAt >= 120000) showSaver();
  }, 1000);
  window.ECKHome = {move: move, activate: activate, back: back, showSaver: showSaver, hideSaver: hideSaver,
    settings: settings, resume: resume, scenes: function () { return scenes; },
    state: function () { return {row: row, col: col, selected: selected, view: view, appPage: appPage}; }};
})();
