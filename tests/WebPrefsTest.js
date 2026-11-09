/*
 * The first top button's setting (Home Settings > Live TV button, web/ on LG and Samsung): the choice kept on the TV
 * is checked, and anything unexpected falls back to Live TV. Run by tools/test-web.sh.
 */
'use strict';
var fs = require('fs'), path = require('path'), vm = require('vm');

function prefs(stored) {
  var sandbox = {
    window: {},
    navigator: {language: 'en-US'},
    localStorage: {
      getItem: function () { return stored === undefined ? null : JSON.stringify(stored); },
      setItem: function () {}
    },
    I18N: {},
    TV: {zone: function () { return ''; }},
    XMLHttpRequest: function () {}
  };
  var code = fs.readFileSync(path.join(__dirname, '..', 'web', 'data.js'), 'utf8');
  vm.runInNewContext(code, sandbox, {filename: 'web/data.js'});
  return sandbox.Prefs;
}

var failures = 0;
function expect(actual, want, label) {
  if (actual === want) return;
  failures++;
  console.error('FAIL ' + label + ': got ' + JSON.stringify(actual) + ', want ' + JSON.stringify(want));
}

expect(prefs({}).boxInput(), 'live', 'nothing picked yet');
expect(prefs({boxInput: 'live'}).boxInput(), 'live', 'Live TV');
expect(prefs({boxInput: 'hdmi2'}).boxInput(), 'hdmi2', 'an HDMI port');
expect(prefs({boxInput: 'HDMI3'}).boxInput(), 'hdmi3', 'case does not matter');
expect(prefs({boxInput: 'hdmi0'}).boxInput(), 'live', 'no port 0');
expect(prefs({boxInput: 'hdmi5'}).boxInput(), 'live', 'no port 5');
expect(prefs({boxInput: 'apple'}).boxInput(), 'live', 'not an input');
expect(prefs({boxInput: 2}).boxInput(), 'live', 'not a string');

expect(prefs({}).liveInput(), true, 'Live TV in the inputs until hidden');
expect(prefs({liveInput: false}).liveInput(), false, 'Live TV hidden');
expect(prefs({liveInput: 'off'}).liveInput(), true, 'not a boolean');

if (failures) process.exit(1);
console.log('WebPrefsTest: all checks passed.');
