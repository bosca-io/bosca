import type { ClientContext } from "@bosca/bml";

/** Native form fields retain local drafts; transport timestamps are explicit instants or all-day dates. */
export function mountCalendarEventFields(ctx: ClientContext) {
  const input = (name: string) => ctx.ref<HTMLInputElement>(name);
  const start=input("start"), end=input("end"), kind=ctx.ref<HTMLSelectElement>("kind"), rule=input("rrule");
  if (!start || !end || !kind || !rule) return;
  const value=JSON.parse(ctx.root.dataset.value ?? "{}");
  const local = (raw:string) => {
    if (!raw) return "";
    const d=new Date(raw); if (!Number.isFinite(d.getTime())) return "";
    return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,"0")}-${String(d.getDate()).padStart(2,"0")}T${String(d.getHours()).padStart(2,"0")}:${String(d.getMinutes()).padStart(2,"0")}`;
  };
  let allDay=kind.value==="all-day";
  const recurrence=ctx.ref<HTMLFieldSetElement>("recurrence");
  const syncRecurrence = () => {
    if (!recurrence) return;
    recurrence.hidden=allDay;
    recurrence.disabled=allDay || recurrence.hasAttribute("data-readonly");
  };
  const setType = () => {
    const next=kind.value==="all-day";
    for (const field of [start,end]) {
      const old=field.value;
      field.type=next?"date":"datetime-local";
      field.value=next?old.slice(0,10):old.length===10?`${old}T09:00`:old;
    }
    allDay=next; syncRecurrence();
  };
  start.value=value.allDay?value.startsAt.slice(0,10):local(value.startsAt);
  end.value=value.allDay?value.endsAt.slice(0,10):local(value.endsAt);
  // Set date types before assigning date-only values, which datetime-local inputs reject.
  if (value.allDay) { start.type=end.type="date"; start.value=value.startsAt.slice(0,10); end.value=value.endsAt.slice(0,10); }
  const originalStart = start.value, originalEnd = end.value;
  const sync = () => {
    const parse = (field:HTMLInputElement) => {
      if (!field.value) return "";
      const original=field===start?value.startsAt:value.endsAt;
      const initial=field===start?originalStart:originalEnd;
      if(!allDay && !value.allDay && field.value===initial && /Z$|[+-]\d\d:\d\d$/.test(original)) return original;
      const d=new Date(field.value);
      return allDay?`${field.value}T00:00:00`:Number.isFinite(d.getTime())?d.toISOString():"";
    };
    const a=input("startValue"),b=input("endValue"),zone=input("zone");
    if(a) a.value=parse(start); if(b) b.value=parse(end);
    if(zone) zone.value=Intl.DateTimeFormat().resolvedOptions().timeZone;
    const invalid=Boolean(a?.value && b?.value && Date.parse(b.value)<=Date.parse(a.value));
    end.setCustomValidity(invalid?"The end must be after the start.":"");
  };
  ctx.on<Event>(kind,"change",()=>{setType();sync();});
  ctx.on<Event>(ctx.root,"input",sync);
  const form=ctx.root.closest("form");
  if(form) ctx.on<Event>(form,"submit",sync,true);
  const frequency=ctx.ref<HTMLSelectElement>("frequency"), days=Array.from(ctx.root.querySelectorAll<HTMLInputElement>("[data-weekday]"));
  const interval=input("interval"),count=input("count"),until=input("until");
  const readRule = () => {
    const parts=Object.fromEntries(rule.value.replace(/^RRULE:/i,"").split(";").filter(Boolean).map(part=>part.split("=")));
    if(frequency) frequency.value=parts.FREQ ?? "";
    if(interval) interval.value=parts.INTERVAL ?? "1";
    if(count) count.value=parts.COUNT ?? "";
    if(until) until.value=parts.UNTIL?`${parts.UNTIL.slice(0,4)}-${parts.UNTIL.slice(4,6)}-${parts.UNTIL.slice(6,8)}`:"";
    for(const day of days) day.checked=(parts.BYDAY ?? "").split(",").includes(day.value);
  };
  const writeRule = () => {
    const parts=frequency?.value?[`FREQ=${frequency.value}`]:[];
    if(parts.length) {
      if(interval?.value && interval.value!=="1") parts.push(`INTERVAL=${interval.value}`);
      const selected=days.filter(day=>day.checked).map(day=>day.value);
      if(selected.length && frequency?.value==="WEEKLY") parts.push(`BYDAY=${selected.join(",")}`);
      if(count?.value) parts.push(`COUNT=${count.value}`);
      else if(until?.value) parts.push(`UNTIL=${until.value.replaceAll("-","")}T235959Z`);
    }
    rule.value=parts.join(";"); rule.dispatchEvent(new Event("input",{bubbles:true}));
  };
  for(const field of [frequency,interval,count,until,...days]) if(field) ctx.on<Event>(field,"change",writeRule);
  ctx.on<Event>(rule,"change",readRule);
  readRule(); sync(); syncRecurrence();
}
