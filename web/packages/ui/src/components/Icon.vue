<script setup lang="ts">
import { computed, type Component } from 'vue'
import {
  Activity, AlertTriangle, AlignCenter, AlignJustify, AlignLeft, AlignRight,
  Archive, ArrowLeft, ArrowRight, ArrowRightLeft, ArrowUpDown, ArrowUpRight,
  Bell, BellPlus, Bold, Book, BookOpen, Boxes, Braces, Brain, Brush, Building2,
  Calendar, Check, CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, ChevronUp,
  ChartNoAxesGantt, CircleHelp, ClipboardList, ClipboardPaste, Clock, Code2, Columns3,
  Container, Copy, CreditCard,
  Database, Download,
  ExternalLink, Eye,
  File, FilePlus, FileSearch, FileText, Filter, Fingerprint, Flag, FlaskConical, Folder, FolderGit2,
  GalleryHorizontalEnd, Gavel, GitBranch, GitCommitHorizontal, GitMerge, GitPullRequest, Globe, Grid3x3, GripVertical,
  Hammer, Hash, Headphones, Heading2, Heading3, HeartPulse, History, Hourglass,
  Image, Inbox, Info, Italic,
  Kanban, Key,
  Languages, Layers, LayoutDashboard, LayoutGrid, Library, Link, List, ListChecks, ListOrdered, ListPlus,
  LoaderCircle, Lock, LogOut,
  Mail, Maximize, Megaphone, Merge, MessageCircle, MessageSquare, Minimize, Minus, Monitor, MoreHorizontal, Moon, Mountain,
  Package, PackageCheck, PackageOpen, PackagePlus, PackageSearch, Paperclip, Pause, Pencil, Pickaxe, PieChart, Pin, Play, Plus, Puzzle,
  Quote,
  Redo2, RefreshCw, Repeat, Reply, Rocket, Rows3, Route, Rss,
  Save, Scale, ScanLine, Search, SearchX, Send, Server, Settings, Share2, Shield, ShieldCheck, ShipWheel, SlidersHorizontal, Smartphone, Sparkle, Sparkles, Split, Stamp, Star, Store, Strikethrough, Subscript, Sun, Superscript,
  Tag, Target, TestTube, Timer, Trash2, TrendingUp, Type,
  Underline, Undo2, Unlock, Upload, User, UserCog, UserMinus, UserPlus, UserSearch, Users,
  Video,
  Wand2, Workflow, Wrench,
  X,
  Zap, ZoomIn, ZoomOut,
} from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  name?: string
  size?: number
  color?: string
  strokeWidth?: number
}>(), {
  name: 'dashboard',
  size: 18,
  color: 'currentColor',
  strokeWidth: 2,
})

const lucideMap: Record<string, Component> = {
  dashboard: LayoutDashboard,
  pulse: Activity,
  activity: Activity,
  boxes: Boxes,
  list: List,
  inspect: ScanLine,
  file: File,
  doc: FileText,
  'file-text': FileText,
  database: Database,
  route: Route,
  rss: Rss,
  book: Book,
  'book-open': BookOpen,
  building: Building2,
  users: Users,
  user: User,
  form: ClipboardList,
  inbox: Inbox,
  wand: Wand2,
  ai: Sparkle,
  sparkles: Sparkles,
  kanban: Kanban,
  check: Check,
  checkmark: Check,
  checkCircle: CheckCircle2,
  'check-circle': CheckCircle2,
  'circle-check': CheckCircle2,
  columns: Columns3,
  folder: Folder,
  'folder-open': Folder,
  languages: Languages,
  message: MessageSquare,
  'message-square': MessageSquare,
  'message-circle': MessageCircle,
  target: Target,
  segment: PieChart,
  megaphone: Megaphone,
  flask: FlaskConical,
  flag: Flag,
  beaker: TestTube,
  calendar: Calendar,
  gear: Settings,
  settings: Settings,
  'settings-2': Settings,
  search: Search,
  plus: Plus,
  play: Play,
  pause: Pause,
  chevron: ChevronRight,
  'chevron-right': ChevronRight,
  chevronDown: ChevronDown,
  'chevron-down': ChevronDown,
  chevronLeft: ChevronLeft,
  'chevron-left': ChevronLeft,
  more: MoreHorizontal,
  'more-horizontal': MoreHorizontal,
  dots: MoreHorizontal,
  minus: Minus,
  filter: Filter,
  sort: ArrowUpDown,
  eye: Eye,
  lock: Lock,
  unlock: Unlock,
  globe: Globe,
  workflow: Workflow,
  zap: Zap,
  image: Image,
  video: Video,
  audio: Headphones,
  grid9: Grid3x3,
  gantt: ChartNoAxesGantt,
  square: Grid3x3,
  shield: Shield,
  saml: ShieldCheck,
  pin: Pin,
  pencil: Pencil,
  edit: Pencil,
  'edit-2': Pencil,
  'edit-3': Pencil,
  tag: Tag,
  star: Star,
  store: Store,
  'credit-card': CreditCard,
  mail: Mail,
  arrowLeft: ArrowLeft,
  'arrow-left': ArrowLeft,
  arrowRight: ArrowRight,
  'arrow-right': ArrowRight,
  arrowUpRight: ArrowUpRight,
  'arrow-up-right': ArrowUpRight,
  x: X,
  'circle-x': X,
  key: Key,
  'key-round': Key,
  reply: Reply,
  device: Smartphone,
  monitor: Monitor,
  passkey: Fingerprint,
  spinner: LoaderCircle,
  loader: LoaderCircle,
  'loader-circle': LoaderCircle,
  alert: AlertTriangle,
  info: Info,
  help: CircleHelp,
  clock: Clock,
  trash: Trash2,
  'trash-2': Trash2,
  link: Link,
  bold: Bold,
  italic: Italic,
  code: Code2,
  underline: Underline,
  strikethrough: Strikethrough,
  alignLeft: AlignLeft,
  alignCenter: AlignCenter,
  alignRight: AlignRight,
  alignJustify: AlignJustify,
  heading2: Heading2,
  heading3: Heading3,
  superscript: Superscript,
  subscript: Subscript,
  quote: Quote,
  listOrdered: ListOrdered,
  listChecks: ListChecks,
  grip: GripVertical,
  move: GripVertical,
  container: Container,
  save: Save,
  copy: Copy,
  'clipboard-copy': Copy,
  clipboard: Copy,
  paste: ClipboardPaste,
  'clipboard-paste': ClipboardPaste,
  download: Download,
  archive: Archive,
  externalLink: ExternalLink,
  'external-link': ExternalLink,
  history: History,
  refresh: RefreshCw,
  'rotate-cw': RefreshCw,
  'rotate-ccw': RefreshCw,
  upload: Upload,
  sun: Sun,
  moon: Moon,
  'log-out': LogOut,
  'git-branch': GitBranch,
  'git-merge': GitMerge,
  merge: Merge,
  split: Split,
  braces: Braces,
  pickaxe: Pickaxe,
  // Lucide has no literal rock; Mountain is the stand-in.
  rock: Mountain,
  'git-pull-request': GitPullRequest,
  'git-commit': GitCommitHorizontal,
  maximize: Maximize,
  minimize: Minimize,
  undo: Undo2,
  redo: Redo2,
  'zoom-in': ZoomIn,
  zoomIn: ZoomIn,
  'zoom-out': ZoomOut,
  zoomOut: ZoomOut,
  bell: Bell,
  notifications: Bell,
  puzzle: Puzzle,
  wrench: Wrench,
  sliders: SlidersHorizontal,
  package: Package,
  library: Library,
  layers: Layers,
  'layout-grid': LayoutGrid,
  layoutGrid: LayoutGrid,
  'rows-3': Rows3,
  rows3: Rows3,
  'gallery-horizontal-end': GalleryHorizontalEnd,
  galleryHorizontalEnd: GalleryHorizontalEnd,
  brush: Brush,
  'paint-brush': Brush,
  chevronUp: ChevronUp,
  'chevron-up': ChevronUp,
  'rectangle-horizontal': Image,
  'rectangle-vertical': Image,
  crop: Image,
  scissors: X,
  kubernetes: ShipWheel,
  // Pipeline node vocabulary (see utils/pipelineNodeIcon.ts in studio).
  'align-left': AlignLeft,
  'arrow-right-left': ArrowRightLeft,
  'bell-plus': BellPlus,
  brain: Brain,
  'file-plus': FilePlus,
  'file-search': FileSearch,
  fingerprint: Fingerprint,
  'folder-git': FolderGit2,
  gavel: Gavel,
  hammer: Hammer,
  hash: Hash,
  'heart-pulse': HeartPulse,
  hourglass: Hourglass,
  'list-checks': ListChecks,
  'list-plus': ListPlus,
  'package-check': PackageCheck,
  'package-open': PackageOpen,
  'package-plus': PackagePlus,
  'package-search': PackageSearch,
  paperclip: Paperclip,
  repeat: Repeat,
  rocket: Rocket,
  scale: Scale,
  'scan-line': ScanLine,
  'search-x': SearchX,
  send: Send,
  server: Server,
  'share-2': Share2,
  'shield-check': ShieldCheck,
  smartphone: Smartphone,
  stamp: Stamp,
  timer: Timer,
  'trending-up': TrendingUp,
  type: Type,
  'undo-2': Undo2,
  'user-cog': UserCog,
  'user-minus': UserMinus,
  'user-plus': UserPlus,
  'user-search': UserSearch,
}

const customPaths: Record<string, string> = {}

const resolved = computed(() => {
  const raw = props.name ?? 'dashboard'
  const name = raw?.toString()?.replace(/^i-lucide-/, '') || ''

  const comp = lucideMap[name] ?? lucideMap[name.toLowerCase()]
  if (comp) return { kind: 'lucide' as const, comp }

  const custom = customPaths[name]
  if (custom) return { kind: 'svg' as const, html: custom }

  return { kind: 'lucide' as const, comp: LayoutDashboard }
})
</script>

<template>
  <component
    :is="resolved.comp"
    v-if="resolved.kind === 'lucide'"
    class="bosca-icon"
    :size="size"
    :color="color"
    :stroke-width="strokeWidth"
  />
  <svg
    v-else
    class="bosca-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    :stroke="color"
    :style="{ color }"
    fill="none"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    shape-rendering="geometricPrecision"
    v-html="resolved.html"
  />
</template>

<style scoped>
.bosca-icon {
  flex-shrink: 0;
}
</style>
