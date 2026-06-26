# TodoDiary

Offline Android app for to-do reminders, diary tracking, sleep tracking, water checks, analysis, and JSON export.

- To-dos have optional notes, weekday selection, and reminder time.
- Done to-dos are recorded as successful completions, limited to one success per to-do per day.
- Diary records optional wake/sleep notes, optional nap, lunch, notes, and 12 workload blocks by two-hour periods.
- Diary workload can auto-fill from successful to-dos completed on the diary date.
- Sleep records store wake time, last night's sleep start, optional note, and calculated sleep duration.
- Water checks are stored as 250 ml entries with exact time and can be deleted.
- Special events can be registered by name and logged multiple times per day with time and note.
- Analysis shows daily and 7-day water/to-do/sleep/workload/event frequency summaries.
- Export writes all local data as JSON to public `Download/TodoDiary`.
- Guide tab explains how to use each screen.

Usage notes:

1. Create to-dos on the To-do screen. Pick weekdays and reminder time with the picker or manual TV time.
2. Tap `Success today` only after a to-do is actually completed. The same task cannot be completed twice on the same date. Diary auto-fill uses those successful completions.
3. Fill Diary optional wake/sleep notes, optional nap, lunch, notes, and two-hour workload blocks.
4. Use Diary `Auto fill` to place successful to-dos into their matching two-hour workload blocks.
5. Use Sleep to record wake time and last night's sleep start for the wake date. Analysis uses this page for sleep duration.
6. Use Water to add each 250 ml check. Delete wrong entries and add them again with the correct time.
7. Use Events to register names such as `headache`, `medicine`, `workout`, or `coffee`, then log every occurrence by date/time. Frequency is counted by the registered name copied into each entry.
8. Use Analysis to review the chosen day and previous 7 days.
9. Use Export to write a local JSON backup. Data stays offline unless you move or share the file.

Date and time input:

- Date fields have `Pick date`, `-1d`, and `+1d` controls so yesterday or tomorrow can be edited quickly.
- Time fields have `Pick time`, `Now`, and `+24 TV` controls.
- Manual TV time is accepted: `28` is normalized to `28:00` and means next-day `04:00` while still belonging to the selected diary/log date.
- TV time range is `00:00` through `47:59`.

Build:

```bash
./gradlew assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```
