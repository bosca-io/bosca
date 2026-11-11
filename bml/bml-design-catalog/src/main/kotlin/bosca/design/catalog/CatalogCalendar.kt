package bosca.design.catalog

import bosca.design.model.CalendarDefinition
import bosca.design.model.CalendarEvent
import bosca.design.model.CalendarParticipant
import java.time.*
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.fortuna.ical4j.model.Recur

/** Local application state demonstrating calendar contracts without a product backend. */
@Serializable
class CatalogCalendar(val id: String) {
    var current = "2026-11-05"
    var view = "month"
    var notice = "Select a date, slot or event."
    var createRequest = 0
    var createOpen = false
    var createCompleted = false
    var createError = ""
    var draft = CalendarEvent("", "", "", "")
    var deleteCompleted = false
    var editingId = ""
    var editingRecurrence = ""
    var editingOccurrence = ""
    var calendarRequest = 0
    var calendarOpen = false
    var calendarCompleted = false
    var calendarError = ""
    var calendarDraft = CalendarDefinition("", "")
    var rejectMoves = false
    var calendars = listOf(CalendarDefinition("work", "Work"), CalendarDefinition("personal", "Personal", "#6479c9"))
    var visibleIds = setOf("work", "personal")
    var events = listOf(
        CalendarEvent("planning", "Planning", "2026-11-05T09:00:00", "2026-11-05T10:00:00", calendarId="work", editable=true),
        CalendarEvent("release", "Release", "2026-11-04T00:00:00", "2026-11-07T00:00:00", allDay=true, calendarId="work", editable=true),
        CalendarEvent("reminder", "Reminder", "2026-11-05T12:00:00", "2026-11-05T12:30:00", calendarId="personal", editable=true),
        CalendarEvent("review", "Review", "2026-11-05T14:00:00", "2026-11-05T15:00:00", calendarId="work", editable=true),
    )
    var excluded = setOf<String>()
    var overrides = mapOf<String, CalendarEvent>()
    val people get() = listOf(CalendarParticipant("alex", "Alex Example"), CalendarParticipant("jamie", "Jamie Example"), CalendarParticipant("taylor", "Taylor Example"))
    val colors get() = calendars.associate { it.id to it.color }
    val editing get() = editingId.isNotBlank()
    val recurring get() = draft.rrule.isNotBlank() || editingRecurrence.isNotBlank()

    fun chooseView(value: String) { require(value in listOf("month", "week", "day")); view = value }
    fun navigate(amount: Int) {
        val date=LocalDate.parse(current)
        current=when(view) { "month" -> date.plusMonths(amount.toLong()); "week" -> date.plusWeeks(amount.toLong()); else -> date.plusDays(amount.toLong()) }.toString()
    }
    fun today() { current=LocalDate.now().toString() }
    fun select(day: String) { current=LocalDate.parse(day).toString(); view="day" }
    fun toggleCalendar(key: String) { require(calendars.any { it.id==key }); visibleIds=if(key in visibleIds) visibleIds-key else visibleIds+key }
    fun editCalendar(key: String) { calendarDraft=calendars.first { it.id==key }; calendarError=""; calendarCompleted=false; calendarOpen=true; calendarRequest++ }
    fun newCalendar() { calendarDraft=CalendarDefinition("", ""); calendarError=""; calendarCompleted=false; calendarOpen=true; calendarRequest++ }
    fun saveCalendar(name: String, color: String, description: String) {
        calendarDraft=calendarDraft.copy(name=name, color=color, description=description)
        calendarError=when { name.isBlank() -> "Enter a calendar name."; !color.matches(Regex("#[0-9a-fA-F]{6}")) -> "Choose a valid color."; else -> "" }
        if(calendarError.isNotEmpty()) return
        val saved=calendarDraft.copy(id=calendarDraft.id.ifBlank { UUID.randomUUID().toString() }, name=name.trim())
        calendars=if(calendars.any { it.id==saved.id }) calendars.map { if(it.id==saved.id) saved else it } else calendars+saved; visibleIds=visibleIds+saved.id; calendarCompleted=true; calendarOpen=false
    }
    fun removeCalendar() {
        val key=calendarDraft.id
        require(calendars.any { it.id==key && it.editable })
        events.filter { it.calendarId==key }.forEach { clearExceptions(it.id) }
        calendars=calendars.filterNot { it.id==key }; events=events.filterNot { it.calendarId==key }; visibleIds=visibleIds-key; calendarCompleted=true; calendarOpen=false
    }
    fun selected(key: String) {
        val occurrence=occurrences().first { it.id==key }
        editingId=occurrence.seriesId.ifBlank { occurrence.id }; editingRecurrence=occurrence.recurrenceId; editingOccurrence=occurrence.startsAt
        draft=occurrence; createError=""; createCompleted=false; deleteCompleted=false; createOpen=true; createRequest++; notice="Selected ${occurrence.title}"
    }
    fun newEvent() { slot(current, 9) }
    fun slot(day: String, hour: Int) {
        require(hour in 0..23)
        val start=LocalDate.parse(day).atTime(hour,0)
        editingId=""; editingRecurrence=""; editingOccurrence=""
        draft=CalendarEvent("", "", start.toString(), start.plusHours(1).toString(), calendarId=calendars.firstOrNull { it.id in visibleIds }?.id ?: calendars.firstOrNull()?.id.orEmpty(), editable=true)
        createError=""; createCompleted=false; deleteCompleted=false; createOpen=true; createRequest++
    }
    fun create(title: String, startsAt: String, endsAt: String, calendarId: String = "work", description: String = "", location: String = "", kind: String = "timed", participantIds: String = "[]", rrule: String = "", timeZone: String = "UTC", scope: String = "this") {
        val participants=Json.decodeFromString(ListSerializer(String.serializer()),participantIds).distinct().map { key ->
            draft.participants.firstOrNull { it.profileId==key } ?: people.first { it.profileId==key }
        }
        draft=draft.copy(title=title,startsAt=startsAt,endsAt=endsAt,calendarId=calendarId,description=description,location=location,allDay=kind=="all-day",participants=participants,rrule=rrule.trim().removePrefix("RRULE:"),timeZone=timeZone)
        createError=if(editingId.isNotBlank() && !master(editingId).editable) "This event is read-only." else validate(draft)
        if(createError.isNotEmpty()) return
        val saved=draft.copy(title=title.trim(),id=editingId.ifBlank { UUID.randomUUID().toString() },editable=true)
        if(editingId.isBlank()) events=events+saved else applyEdit(saved,scope)
        current=local(saved.startsAt,saved.timeZone).toLocalDate().toString()
        visibleIds=visibleIds+saved.calendarId; createCompleted=true; createOpen=false; notice=if(editingId.isBlank()) "Event added." else "Event saved."
    }
    private fun validate(value: CalendarEvent): String {
        try { ZoneId.of(value.timeZone.ifBlank { "UTC" }) } catch(_: DateTimeException) { return "Choose a valid time zone." }
        if(value.title.isBlank()) return "Enter an event title."
        if(calendars.none { it.id==value.calendarId }) return "Choose a calendar."
        val start=try { local(value.startsAt,value.timeZone) } catch(_: DateTimeException) { return "Enter valid start and end times." }
        val end=try { local(value.endsAt,value.timeZone) } catch(_: DateTimeException) { return "Enter valid start and end times." }
        val positive = try { Instant.parse(value.endsAt)>Instant.parse(value.startsAt) } catch(_: DateTimeParseException) { end>start }
        if(!positive) return "The end must be after the start."
        if(value.rrule.isNotBlank()) try { Recur<LocalDateTime>(value.rrule) } catch(_: IllegalArgumentException) { return "Enter a valid recurrence rule." }
        return ""
    }
    private fun local(raw: String, zone: String): LocalDateTime = if(raw.length==10) LocalDate.parse(raw).atStartOfDay() else try { Instant.parse(raw).atZone(ZoneId.of(zone.ifBlank { "UTC" })).toLocalDateTime() } catch(_: DateTimeParseException) { LocalDateTime.parse(raw) }
    private fun stamp(value: LocalDateTime, event: CalendarEvent): String = if(event.allDay) value.toString() else value.atZone(ZoneId.of(event.timeZone.ifBlank { "UTC" })).toInstant().toString()
    private fun master(key: String) = events.first { it.id==key }
    private fun occurrenceKey(event: CalendarEvent, start: String) = "${event.id}@$start"

    /** Expands only the visible range; recurrence and persistence remain application responsibilities. */
    fun occurrences(): List<CalendarEvent> {
        val date=LocalDate.parse(current)
        val from=when(view) { "month" -> date.withDayOfMonth(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); "week" -> date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); else -> date }
        val to=from.plusDays(if(view=="month") 42 else if(view=="week") 7 else 1)
        val expanded = events.flatMap { event ->
            if(event.rrule.isBlank()) listOf(event) else {
                val seed=local(event.startsAt,event.timeZone); val duration=Duration.between(seed,local(event.endsAt,event.timeZone))
                val search=from.atStartOfDay().minus(duration)
                Recur<LocalDateTime>(event.rrule).getDates(seed,if(search<seed) seed else search,to.atStartOfDay(),1000).filter { it < to.atStartOfDay() }.mapNotNull { start ->
                    val recurrence=stamp(start,event); val key=occurrenceKey(event,recurrence)
                    if(key in excluded) null else overrides[key] ?: event.copy(id=key,seriesId=event.id,recurrenceId=recurrence,startsAt=recurrence,endsAt=stamp(start.plus(duration),event))
                }
            }
        }
        val keys=expanded.map { it.id }.toSet()
        val extra=overrides.filter { (key,event) -> key !in keys && key !in excluded && events.any { it.id==event.seriesId } }.values
        return (expanded+extra).filter { it.calendarId in visibleIds && local(it.startsAt,it.timeZone)<to.atStartOfDay() && local(it.endsAt,it.timeZone)>from.atStartOfDay() }
    }
    private fun applyEdit(saved: CalendarEvent, scope: String) {
        require(scope in listOf("this","following","all"))
        val original=master(editingId)
        if(original.rrule.isBlank()) { events=events.map { if(it.id==editingId) saved else it }; return }
        val boundary=local(editingRecurrence,original.timeZone)
        val key=occurrenceKey(original,editingRecurrence)
        if(scope=="this") { overrides=overrides+(key to saved.copy(id=key,seriesId=original.id,recurrenceId=editingRecurrence,rrule=original.rrule)); return }
        val delta=Duration.between(local(editingOccurrence,original.timeZone),local(saved.startsAt,saved.timeZone))
        val duration=Duration.between(local(saved.startsAt,saved.timeZone),local(saved.endsAt,saved.timeZone))
        if(scope=="all" || boundary==local(original.startsAt,original.timeZone)) {
            val start=local(original.startsAt,original.timeZone).plus(delta)
            val replacement=saved.copy(id=original.id,seriesId="",recurrenceId="",startsAt=stamp(start,saved),endsAt=stamp(start.plus(duration),saved))
            events=events.map { if(it.id==original.id) replacement else it }; clearExceptions(original.id)
        } else {
            val replacement=saved.copy(id=UUID.randomUUID().toString(),seriesId="",recurrenceId="",rrule=remainingRule(original,boundary,saved.rrule))
            events=events.map { if(it.id==original.id) it.copy(rrule=until(original,boundary)) else it }+replacement
            excluded=excluded.filterNot { it.startsWith("${original.id}@") && local(it.substringAfter('@'),original.timeZone)>=boundary }.toSet()
            overrides=overrides.filterNot { it.key.startsWith("${original.id}@") && local(it.key.substringAfter('@'),original.timeZone)>=boundary }
        }
    }
    private fun clearExceptions(id: String) { excluded=excluded.filterNot { it.startsWith("$id@") }.toSet(); overrides=overrides.filterNot { it.key.startsWith("$id@") } }
    private fun until(event: CalendarEvent, boundary: LocalDateTime): String = event.rrule.split(';').filterNot { it.startsWith("COUNT=") || it.startsWith("UNTIL=") }.joinToString(";")+";UNTIL="+boundary.minusSeconds(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    private fun remainingRule(original: CalendarEvent, boundary: LocalDateTime, rule: String): String {
        if(rule!=original.rrule) return rule
        val count=rule.split(';').firstOrNull { it.startsWith("COUNT=") }?.substringAfter('=')?.toInt() ?: return rule
        val seed=local(original.startsAt,original.timeZone)
        val before=Recur<LocalDateTime>(rule).getDates(seed,seed,boundary,1000).count { it < boundary }
        return rule.split(';').joinToString(";") { if(it.startsWith("COUNT=")) "COUNT=${(count-before).coerceAtLeast(1)}" else it }
    }
    fun remove(scope: String) {
        val original=master(editingId); require(original.editable); require(scope in listOf("this","following","all"))
        if(original.rrule.isBlank() || scope=="all" || (scope=="following" && local(editingRecurrence,original.timeZone)==local(original.startsAt,original.timeZone))) { events=events.filterNot { it.id==editingId }; clearExceptions(editingId) }
        else if(scope=="this") { excluded=excluded+occurrenceKey(original,editingRecurrence); overrides=overrides-occurrenceKey(original,editingRecurrence) }
        else {
            val boundary=local(editingRecurrence,original.timeZone)
            events=events.map { if(it.id==editingId) it.copy(rrule=until(it,boundary)) else it }
            excluded=excluded.filterNot { it.startsWith("${original.id}@") && local(it.substringAfter('@'),original.timeZone)>=boundary }.toSet()
            overrides=overrides.filterNot { it.key.startsWith("${original.id}@") && local(it.key.substringAfter('@'),original.timeZone)>=boundary }
        }
        createCompleted=true; deleteCompleted=true; createOpen=false; notice="Event deleted."
    }
    fun dismissEditor() { createOpen=false }
    fun dismissCalendar() { calendarOpen=false }
    fun toggleRejection() { rejectMoves=!rejectMoves }
    fun move(key: String, startsAt: String, endsAt: String) {
        check(!rejectMoves) { "Example save rejected." }
        val occurrence=occurrences().first { it.id==key }; require(occurrence.editable)
        require(local(endsAt,occurrence.timeZone)>local(startsAt,occurrence.timeZone))
        if(occurrence.seriesId.isBlank()) events=events.map { if(it.id==key) it.copy(startsAt=startsAt,endsAt=endsAt) else it }
        else overrides=overrides+(key to occurrence.copy(startsAt=startsAt,endsAt=endsAt))
        notice="Event moved."
    }
}
