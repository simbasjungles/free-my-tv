/*
 * On-screen text in the viewer's language, from the Android app's catalogues (app/assets/i18n/<lang>.json, packed into
 * i18n.js by tools/webapp.py), keyed by the English text in the code: tr('Live TV'). A key "context|Text" is "Text" where
 * a language needs another word. Text built at runtime is a pattern with named placeholders: trf('{n} min ago', {n: 12});
 * a count is plural('plural|one:{n} season|other:{n} seasons', 3); a date or time is formatDate('format|h:mm a', at).
 * tools/translate.py keeps the catalogues in step with this code and the Android app's.
 * Written for the Chromium of older TVs (webOS 3, Tizen 3): ES5, and Intl only where it's there.
 */
var I18N = (function () {
  'use strict';

  var CATALOGUES = window.I18N_CATALOGUES || {};
  var lang = 'en', catalogue = {}, locale = 'en-US', pluralRules = null;
  var has = Object.prototype.hasOwnProperty;

  /** The usual country for each language's dates and numbers, when the TV itself is in another language. */
  var REGIONS = {pt: 'BR', es: 'ES', de: 'DE', fr: 'FR', it: 'IT', nl: 'NL', pl: 'PL', sv: 'SE', da: 'DK', nb: 'NO',
    fi: 'FI', cs: 'CZ', ro: 'RO', tr: 'TR', ru: 'RU', uk: 'UA', el: 'GR', ja: 'JP', ko: 'KR', hi: 'IN', id: 'ID', vi: 'VN', th: 'TH'};

  /** Each language in its own name, for TVs without Intl.DisplayNames (Chromium before 81). */
  var NAMES = {en: 'English', pt: 'Português', es: 'Español', de: 'Deutsch', fr: 'Français', it: 'Italiano',
    nl: 'Nederlands', pl: 'Polski', sv: 'Svenska', da: 'Dansk', nb: 'Norsk bokmål', fi: 'Suomi', cs: 'Čeština',
    ro: 'Română', tr: 'Türkçe', ru: 'Русский', uk: 'Українська', el: 'Ελληνικά', ja: '日本語', ko: '한국어',
    'zh-CN': '简体中文', 'zh-TW': '繁體中文', hi: 'हिन्दी', id: 'Indonesia', vi: 'Tiếng Việt', th: 'ไทย'};

  /**
   * The CLDR plural category of a whole number, for TVs without Intl.PluralRules (Chromium before 63). Only the
   * languages whose catalogues use more than "one" and "other" need a rule; the rest are one for 1, else other.
   */
  var PLURALS = {
    ru: slavic,
    uk: slavic,
    pl: function (n) {
      var t = n % 10, h = n % 100;
      return n === 1 ? 'one' : t >= 2 && t <= 4 && (h < 12 || h > 14) ? 'few' : 'many';
    },
    cs: function (n) { return n === 1 ? 'one' : n >= 2 && n <= 4 ? 'few' : 'other'; },
    ro: function (n) { var h = n % 100; return n === 1 ? 'one' : n === 0 || (h >= 1 && h <= 19) ? 'few' : 'other'; }
  };

  function slavic(n) {
    var t = n % 10, h = n % 100;
    return t === 1 && h !== 11 ? 'one' : t >= 2 && t <= 4 && (h < 12 || h > 14) ? 'few' : 'many';
  }

  /** The text for this English key in the current language (the English itself when there is none). */
  function tr(english) {
    if (has.call(catalogue, english)) return catalogue[english];
    var bar = english.indexOf('|');
    if (bar < 0) return english;
    var plain = english.substring(bar + 1);
    return has.call(catalogue, plain) ? catalogue[plain] : plain;
  }

  /** A pattern with named placeholders, filled from an object: trf('{n} min ago', {n: 12}). */
  function trf(english, values) {
    return tr(english).replace(/\{([a-zA-Z_][a-zA-Z_0-9]*)\}/g, function (all, name) {
      return values && has.call(values, name) ? String(values[name]) : all;
    });
  }

  /**
   * A count with its word in the right plural form: plural('plural|one:{n} season|other:{n} seasons', 3). Each catalogue
   * gives one form per plural category its language uses; a missing one falls back to "other", then to the last.
   */
  function plural(key, n) {
    var forms = tr(key).split('|'), category = pluralOf(n), chosen = null, other = null, last = '';
    for (var i = 0; i < forms.length; i++) {
      var colon = forms[i].indexOf(':'), name = colon < 0 ? '' : forms[i].substring(0, colon);
      last = colon < 0 ? forms[i] : forms[i].substring(colon + 1);
      if (name === category) chosen = last;
      if (name === 'other') other = last;
    }
    return (chosen !== null ? chosen : other !== null ? other : last).replace(/\{n\}/g, String(n));
  }

  function pluralOf(n) {
    if (pluralRules) return pluralRules.select(n);
    var rule = PLURALS[base(lang)];
    return rule ? rule(Math.abs(n)) : n === 1 ? 'one' : 'other';
  }

  // MARK: - Dates and times, from the catalogues' SimpleDateFormat patterns

  /**
   * A date or time in the catalogue's pattern for a "format|..." key, as Java's SimpleDateFormat writes it on Android:
   * formatDate('format|EEEE, MMMM d', at). Names come from the TV's own Intl; a pattern with a letter this doesn't
   * know falls back to the English one, so a catalogue can't break the clock.
   */
  function formatDate(key, at) {
    var minute = Math.floor(at / 60000), last = formatted[key];
    if (last && last.minute === minute && last.lang === lang) return last.text;   // the clocks ask every second
    var d = new Date(at), out = format(tr(key), d, locale);
    if (out === null) out = format(key.substring(key.indexOf('|') + 1), d, 'en-US');
    formatted[key] = {minute: minute, lang: lang, text: out};
    return out;
  }

  /** The last text of each "format|..." key, by minute (the catalogues' patterns go down to minutes). */
  var formatted = {};

  function format(pattern, d, loc) {
    var out = '', i = 0;
    while (i < pattern.length) {
      var ch = pattern.charAt(i);
      if (ch === "'") {
        if (pattern.charAt(i + 1) === "'") { out += "'"; i += 2; continue; }
        // Quoted text, where '' is a quote: "h 'o''clock'".
        for (i++; i < pattern.length; i++) {
          if (pattern.charAt(i) !== "'") out += pattern.charAt(i);
          else if (pattern.charAt(i + 1) === "'") out += pattern.charAt(i++);
          else break;
        }
        if (i >= pattern.length) return null;
        i++;
        continue;
      }
      if (!/[A-Za-z]/.test(ch)) { out += ch; i++; continue; }
      var n = 1;
      while (pattern.charAt(i + n) === ch) n++;
      var field = part(ch, n, d, loc);
      if (field === null) return null;
      out += field;
      i += n;
    }
    return out;
  }

  function part(ch, n, d, loc) {
    var h = d.getHours();
    switch (ch) {
      case 'y': return n === 2 ? pad(d.getFullYear() % 100, 2) : pad(d.getFullYear(), n);
      case 'M': case 'L': return n >= 4 ? month(d, loc, ch === 'M') : n === 3 ? name(d, loc, {month: 'short'}) : pad(d.getMonth() + 1, n);
      case 'd': return pad(d.getDate(), n);
      case 'E': return name(d, loc, {weekday: n >= 4 ? 'long' : 'short'});
      case 'a': return dayPeriod(d, loc);
      case 'H': return pad(h, n);
      case 'k': return pad(h || 24, n);
      case 'K': return pad(h % 12, n);
      case 'h': return pad(h % 12 || 12, n);
      case 'm': return pad(d.getMinutes(), n);
      case 's': return pad(d.getSeconds(), n);
      default: return null;
    }
  }

  function pad(v, n) {
    var s = String(v);
    while (s.length < n) s = '0' + s;
    return s;
  }

  function name(d, loc, options) {
    try { return d.toLocaleDateString(loc, options); } catch (e) { return d.toLocaleDateString('en-US', options); }
  }

  /**
   * The month's full name. Next to a day ("MMMM", the format form) some languages decline it, as Java does: "21 октября",
   * not "октябрь". That form is the TV's own "day month" text without the day, unless the plain name is a word of it
   * ("21 de octubre" → "octubre").
   */
  function month(d, loc, withDay) {
    var plain = name(d, loc, {month: 'long'});
    if (!withDay) return plain;
    var both = name(d, loc, {month: 'long', day: 'numeric'});
    var rest = both.replace(new RegExp('(^|\\s)' + d.getDate() + '\\.?(?=\\s|$)'), ' ').replace(/^\s+|\s+$/g, '');
    if (!rest || rest === both) return plain;
    var words = rest.split(/\s+/);
    for (var i = 0; i < words.length; i++) if (words[i] === plain) return plain;
    return rest;
  }

  /** AM or PM in the language ("오후" in Korean), where the TV's Intl can say it. */
  function dayPeriod(d, loc) {
    try {
      var parts = new Intl.DateTimeFormat(loc, {hour: 'numeric', hour12: true}).formatToParts(d);
      for (var i = 0; i < parts.length; i++) if (parts[i].type === 'dayPeriod') return parts[i].value;
    } catch (e) { /* older Chromium: no formatToParts */ }
    return d.getHours() < 12 ? 'AM' : 'PM';
  }

  // MARK: - Picking the language

  /** "en" and every language with a catalogue, sorted by code. */
  function languages() {
    var all = ['en'];
    for (var code in CATALOGUES) if (has.call(CATALOGUES, code)) all.push(code);
    return all.sort();
  }

  /** The language's own name: "Português", "Deutsch", "日本語". */
  function languageName(code) {
    if (has.call(NAMES, code)) return NAMES[code];
    try {
      var own = new Intl.DisplayNames([code], {type: 'language'}).of(code);
      return own.charAt(0).toUpperCase() + own.substring(1);
    } catch (e) {
      return code;
    }
  }

  /** The catalogue for a setting ("auto", "fr", "pt-BR"…), or for the TV's language with "auto"; else "en". */
  function resolve(setting) {
    if (setting && setting !== 'auto') {
      var code = match(setting.replace('_', '-'));
      if (code) return code;
    }
    var tv = (navigator.language || 'en').replace('_', '-');
    // Taiwan, Hong Kong and Macau write Traditional Chinese: never the Simplified catalogue there.
    if (base(tv) === 'zh' && /-(Hant|TW|HK|MO)\b/i.test(tv)) return CATALOGUES['zh-TW'] ? 'zh-TW' : 'en';
    return match(base(tv)) || 'en';
  }

  function match(tag) {
    var all = languages(), lower = tag.toLowerCase(), first = base(lower);
    for (var i = 0; i < all.length; i++) if (all[i].toLowerCase() === lower) return all[i];
    for (var j = 0; j < all.length; j++) if (base(all[j]) === first) return all[j];
    return null;
  }

  /** The language without its country ("zh" for "zh-CN"), with Java's old codes for Indonesian and Hebrew, and Norwegian. */
  function base(code) {
    var l = code.split('-')[0].toLowerCase();
    return l === 'in' ? 'id' : l === 'iw' ? 'he' : l === 'no' || l === 'nn' ? 'nb' : l;
  }

  /** The BCP 47 tag for dates and numbers: the TV's own country when it is in this language (es-MX, de-AT…). */
  function localeOf(code) {
    if (code === 'en') return 'en-US';
    if (code === 'pt') return 'pt-BR';
    if (code.indexOf('-') > 0) return code;
    var tv = navigator.language || '';
    if (base(tv) === code && tv.indexOf('-') > 0) return tv;
    return REGIONS[code] ? code + '-' + REGIONS[code] : code;
  }

  return {
    /** After the language changes in Home Settings (and once at startup). */
    set: function (setting) {
      lang = resolve(setting);
      catalogue = lang === 'en' ? {} : CATALOGUES[lang] || {};
      locale = localeOf(lang);
      try { pluralRules = new Intl.PluralRules(locale); } catch (e) { pluralRules = null; }
      document.documentElement.lang = locale;
    },
    /** The catalogue in use: "en", "pt", "zh-CN"… */
    get lang() { return lang; },
    /** The language without its country, as TMDB names it ("zh" for "zh-CN"). */
    get base() { return base(lang); },
    /** BCP 47 tag for dates, numbers and TMDB. */
    locale: function () { return locale; },
    languages: languages,
    languageName: languageName,
    tr: tr,
    trf: trf,
    plural: plural,
    formatDate: formatDate
  };
})();

function tr(english) {
  return I18N.tr(english);
}

function trf(english, values) {
  return I18N.trf(english, values);
}

function plural(key, n) {
  return I18N.plural(key, n);
}

function formatDate(key, at) {
  return I18N.formatDate(key, at);
}
