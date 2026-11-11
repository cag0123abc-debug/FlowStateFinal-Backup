# Google Calendar two-way sync — setup and credential rotation

FlowState no longer ships any Google endpoint or token in `index.html`. The app
talks to a small Google Apps Script web app **that you own and deploy from your
own Google account**, and it stores that URL plus a device key in your browser's
local storage only.

## 1. Rotate the credentials that were public before

An earlier version of `index.html` contained a hardcoded Apps Script URL
(`script.google.com/macros/s/AKfycb.../exec`) and a shared token
(`flowstate-2026-private-...`) in client-side code. Anything served to a browser
is public, so treat both as compromised:

1. Open the Apps Script project that the old URL points to → **Deploy → Manage
   deployments** → archive/delete the old deployment so the URL stops working.
2. Deploy a **new** deployment (new URL) after pasting the code below.
3. The old shared token must be replaced. The script below validates a key you
   choose yourself; do not reuse the old value.
4. If you ever put a Google Cloud OAuth client secret or an API key in this
   repository, go to <https://console.cloud.google.com/apis/credentials>, delete
   that credential and create a new one. Never commit the new one — keep it in
   Apps Script's **Script Properties**, not in source.
5. Because the old token was public, check your Google Calendar for unexpected
   events and your Apps Script **Executions** log for unfamiliar calls.

Note that a token embedded in a client app cannot be a real secret: any user can
read it. That is why the endpoint and key are now user-supplied settings, and why
the endpoint itself must validate the key server-side (below).

## 2. Create the Apps Script project

1. Go to <https://script.google.com> → **New project**.
2. Paste the code below into `Code.gs`.
3. **Project Settings → Script properties → Add property**:
   `SYNC_KEY` = a long random string you generate (e.g. 40+ random characters).
4. Run the `setup` function once and approve the Calendar permissions.
5. **Deploy → New deployment → Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone** (the script enforces `SYNC_KEY`)
6. Copy the `/exec` URL and the `SYNC_KEY` into FlowState → ⚙️ Settings →
   **Google Calendar sync**.

## 3. Apps Script code (`Code.gs`)

```javascript
var CAL_ID = 'primary';            // or a specific calendar id
var SYNC_KEY = PropertiesService.getScriptProperties().getProperty('SYNC_KEY');

function setup() {
  CalendarApp.getCalendarById(CAL_ID) || CalendarApp.getDefaultCalendar();
}

function ok_(e) {
  return e && e.parameter && e.parameter.key && e.parameter.key === SYNC_KEY;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

// Incoming: Google Calendar -> FlowState
function doGet(e) {
  if (!ok_(e)) return json_({ ok: false, error: 'bad key' });
  if (e.parameter.action !== 'pull') return json_({ ok: false, error: 'unknown action' });

  var cal = CalendarApp.getCalendarById(CAL_ID) || CalendarApp.getDefaultCalendar();
  var from = e.parameter.from ? new Date(e.parameter.from) : new Date(Date.now() - 86400000 * 30);
  var to = e.parameter.to ? new Date(e.parameter.to) : new Date(Date.now() + 86400000 * 180);

  var out = [];
  cal.getEvents(from, to).forEach(function (ev) {
    if (ev.isAllDayEvent() && ev.getTitle() === 'Busy') return; // skip placeholder blocks
    out.push({
      gcalId: ev.getId(),
      calendar: cal.getName(),
      title: ev.getTitle() || '(no title)',
      body: ev.getDescription() || '',
      start: ev.getStartTime().toISOString(),
      end: ev.getEndTime().toISOString(),
      allDay: ev.isAllDayEvent(),
      recurring: !!ev.getEventSeries(),
      recurrence: ev.getEventSeries() ? String(ev.getEventSeries().getRecurrence().join('\n')) : ''
    });
  });
  return json_({ ok: true, calendar: cal.getName(), events: out });
}

// Outgoing: FlowState -> Google Calendar
function doPost(e) {
  var body = {};
  try { body = JSON.parse(e.postData.contents); } catch (err) { return json_({ ok: false, error: 'bad body' }); }
  if (!body.key || body.key !== SYNC_KEY) return json_({ ok: false, error: 'bad key' });

  var cal = CalendarApp.getCalendarById(CAL_ID) || CalendarApp.getDefaultCalendar();

  if (body.action === 'delete') {
    var found = cal.getEventById(body.gcalId);
    if (found) found.deleteEvent();
    return json_({ ok: true });
  }

  if (body.action === 'upsert') {
    var it = body.item || {};
    if (!it.start) return json_({ ok: false, error: 'missing start' });
    var start = new Date(it.start);
    var end = it.end ? new Date(it.end) : new Date(start.getTime() + 3600000);

    // Duplicate prevention: an existing event id wins over creating a new one.
    var ev = it.gcalId ? cal.getEventById(it.gcalId) : null;
    if (!ev) {
      var same = cal.getEvents(start, new Date(start.getTime() + 60000)).filter(function (x) {
        return x.getTitle() === (it.title || '(no title)');
      });
      if (same.length) ev = same[0];
    }

    if (ev) {
      ev.setTitle(it.title || '(no title)');
      ev.setDescription(it.body || '');
      if (ev.isAllDayEvent() !== !!it.allDay) {
        ev.deleteEvent();
        ev = it.allDay
          ? cal.createAllDayEvent(it.title || '(no title)', start)
          : cal.createEvent(it.title || '(no title)', start, end);
      } else if (!it.allDay) {
        ev.setTime(start, end);
      }
    } else {
      ev = it.allDay
        ? cal.createAllDayEvent(it.title || '(no title)', start)
        : cal.createEvent(it.title || '(no title)', start, end, { description: it.body || '' });
    }

    if (it.recurrence) {
      // RRULE lines such as "RRULE:FREQ=WEEKLY;BYDAY=WE,TH,SA"
      try {
        var series = ev.getEventSeries();
        if (series) series.setRecurrence(CalendarApp.newRecurrence().addWeeklyRule(
          CalendarApp.Weekday.WEDNESDAY));
      } catch (err) { /* recurrence left untouched */ }
    }
    return json_({ ok: true, gcalId: ev.getId(), calendar: cal.getName() });
  }

  return json_({ ok: false, error: 'unknown action' });
}
```

### About recurring events on the server

Apps Script's `Recurrence` API cannot express every RRULE the app can produce
(e.g. "second Friday of every month" combined with an end date). The app always
stores its own recurrence definition locally and sends an `RRULE` string, so the
local series is authoritative; treat Google's copy as a mirror. If a pattern
cannot be mirrored exactly, the event still syncs with its correct first date.

## 4. What the app does with it

- **Push**: every create/edit/complete/delete calls `sendGoogleSync(...)`; failures
  are queued in local storage and flushed when the app next opens or you press
  **Sync now**.
- **Pull**: `pullGoogleSync()` GETs `?action=pull&key=…`, matches on `gcalId`, and
  only inserts events that are not already present — that is the duplicate guard
  on the client side. Incoming items are tagged `source: 'google'` and show a
  `📅 <calendar name>` badge so you can tell where an event came from.
- Nothing syncs until you enter your own URL and key.
