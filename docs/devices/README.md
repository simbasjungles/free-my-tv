# TV reports

One short file per TV model that someone ran Free My TV on, so the next person with the same set knows what to expect
before they start.

Open an issue with the **My TV (works or doesn't)** form to add one. A maintainer turns a good report into
`docs/devices/<brand>-<model>.md`, with these headings:

- **Device:** brand, model, year, platform and OS version.
- **Free My TV version** the report is for.
- **Install:** what the TV needed (USB debugging only, pairing, a Developer Mode app).
- **Works:** what was checked and fine.
- **Doesn't work / quirks:** what failed and the workaround, if there is one.

Nothing private goes in a report: no TMDB key, Plex token, Wi-Fi name or account.

There are no model files yet: the first ones come from the reports already in the issue tracker (search the label
`tv-report`, or titles starting with "Compatibility report").
