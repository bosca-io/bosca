lexer grammar USXLexer;

// ============================================================
// DEFAULT MODE (Content Text)
// ============================================================

// Skip Byte Order Mark (BOM) to fix "extraneous input" errors
BOM : '\uFEFF' -> skip;

// Skip XML Declaration
XML_PROLOG : '<?xml' .*? '?>' -> skip;

// Skip top-level whitespace (between prolog and root, or usually ignored in XML structure)
// This MUST be defined before TEXT to ensure pure whitespace is skipped.
WS_DEFAULT : [ \t\r\n]+ -> skip;

START_TAG_OPEN : '<' -> pushMode(TAG);
END_TAG_OPEN   : '</' -> pushMode(TAG);

// Matches text content between tags (non-whitespace or mixed).
TEXT : ~[<]+;


// ============================================================
// TAG MODE (Inside <...>)
// ============================================================
mode TAG;

WS : [ \t\r\n]+ -> skip;

TAG_CLOSE       : '>' -> popMode;
TAG_SLASH_CLOSE : '/>' -> popMode;
EQ              : '=';

// --- Element Names ---
KW_USX      : 'usx';
KW_BOOK     : 'book';
KW_PARA     : 'para';
KW_TABLE    : 'table';
KW_ROW      : 'row';
KW_CELL     : 'cell';
KW_CHAR     : 'char';
KW_MS       : 'ms';
KW_CHAPTER  : 'chapter';
KW_VERSE    : 'verse';
KW_NOTE     : 'note';
KW_SIDEBAR  : 'sidebar';
KW_FIGURE   : 'figure';
KW_REF      : 'ref';
KW_OPTBREAK : 'optbreak';
KW_PERIPH   : 'periph';

// --- Attribute Names ---
KW_VERSION    : 'version';
KW_XSI_SCHEMA : 'xsi:noNamespaceSchemaLocation';
KW_CODE       : 'code';
KW_STYLE      : 'style';
KW_VID        : 'vid';
KW_ID         : 'id';
KW_ALT        : 'alt';
KW_ALIGN      : 'align';
KW_COLSPAN    : 'colspan';
KW_CLOSED     : 'closed';
KW_LINK_HREF  : 'link-href';
KW_LINK_TITLE : 'link-title';
KW_LINK_ID    : 'link-id';
KW_LEMMA      : 'lemma';
KW_STRONG     : 'strong';
KW_SRCLOC     : 'srcloc';
KW_GLOSS      : 'gloss';
KW_SID        : 'sid';
KW_EID        : 'eid';
KW_WHO        : 'who';
KW_NUMBER     : 'number';
KW_ALTNUMBER  : 'altnumber';
KW_PUBNUMBER  : 'pubnumber';
KW_CALLER     : 'caller';
KW_CATEGORY   : 'category';
KW_FILE       : 'file';
KW_SIZE       : 'size';
KW_LOC        : 'loc';
KW_COPY       : 'copy';

// --- Attribute Values (Strict Enums) ---

// Shared/Overlapping Values
VAL_REM : '"rem"';
VAL_CL  : '"cl"';
VAL_XT  : '"xt"';
VAL_FV  : '"fv"';
VAL_USFM: '"usfm"';
VAL_ID  : '"id"';

// Shared Title/Intro headers
VAL_MT_ALL  : '"mt"' | '"mt1"' | '"mt2"' | '"mt3"' | '"mt4"';
VAL_IMT_ALL : '"imt"' | '"imt1"' | '"imt2"' | '"imt3"' | '"imt4"';

// Shared Formatting Chars
VAL_FORMAT  : '"it"' | '"bd"' | '"bdit"' | '"em"' | '"sc"';

// Header Styles
VAL_STYLE_HEADER
    : '"ide"' | '"h"' | '"h1"' | '"h2"' | '"h3"'
    | '"toc1"' | '"toc2"' | '"toc3"' | '"toca1"' | '"toca2"' | '"toca3"'
    ;

// Intro Styles
VAL_STYLE_INTRO
    : '"ib"' | '"ie"' | '"ili"' | '"ili1"' | '"ili2"'
    | '"im"' | '"imi"' | '"imq"' | '"io"' | '"io1"' | '"io2"' | '"io3"' | '"io4"' | '"iot"'
    | '"ip"' | '"ipi"' | '"ipq"' | '"ipr"' | '"iq"' | '"iq1"' | '"iq2"' | '"iq3"'
    | '"is"' | '"is1"' | '"is2"' | '"imte"' | '"imte1"' | '"imte2"' | '"iex"'
    ;

// Paragraph Styles
VAL_STYLE_PARA
    : '"restore"' | '"cls"' | '"lit"' | '"m"' | '"mi"' | '"nb"' | '"p"' | '"pb"' | '"pc"'
    | '"pi"' | '"pi1"' | '"pi2"' | '"pi3"' | '"po"' | '"pr"' | '"pmo"' | '"pm"' | '"pmc"' | '"pmr"'
    | '"ph"' | '"ph1"' | '"ph2"' | '"ph3"' | '"q"' | '"q1"' | '"q2"' | '"q3"' | '"q4"'
    | '"qa"' | '"qc"' | '"qr"' | '"qm"' | '"qm1"' | '"qm2"' | '"qm3"' | '"qd"'
    | '"b"' | '"d"' | '"ms"' | '"ms1"' | '"ms2"' | '"ms3"' | '"mr"' | '"r"'
    | '"s"' | '"s1"' | '"s2"' | '"s3"' | '"s4"' | '"sr"' | '"sp"'
    | '"sd"' | '"sd1"' | '"sd2"' | '"sd3"' | '"sd4"'
    | '"ts"' | '"cp"' | '"cd"' | '"mte"' | '"mte1"' | '"mte2"'
    | '"p1"' | '"p2"' | '"k1"' | '"k2"'
    ;

VAL_STYLE_LIST : '"lh"' | '"li"' | '"li1"' | '"li2"' | '"li3"' | '"li4"' | '"lf"' | '"lim"' | '"lim1"' | '"lim2"' | '"lim3"' | '"lim4"';

VAL_STYLE_TABLE : '"tr"';
VAL_STYLE_CELL  : '"t' [hc] [rc]? [0-9]+ '"';

VAL_STYLE_INTRO_CHAR : '"ior"' | '"iqt"';

// Char Styles
VAL_STYLE_CHAR
    : '"va"' | '"vp"' | '"ca"' | '"qac"' | '"qs"' | '"add"' | '"addpn"' | '"bk"' | '"dc"'
    | '"efm"' | '"fm"' | '"k"' | '"nd"' | '"ndx"' | '"ord"' | '"pn"' | '"png"' | '"pro"'
    | '"qt"' | '"rq"' | '"sig"' | '"sls"' | '"tl"' | '"wg"' | '"wh"' | '"wa"' | '"wj"'
    | '"jmp"' | '"no"' | '"sup"'
    ;

VAL_STYLE_W  : '"w"';
VAL_STYLE_RB : '"rb"';

VAL_STYLE_LIST_CHAR : '"litl"' | '"lik"' | '"liv"' | '"liv1"' | '"liv2"' | '"liv3"' | '"liv4"' | '"liv5"';

VAL_STYLE_NOTE      : '"f"' | '"fe"' | '"ef"';
VAL_STYLE_NOTE_CHAR : '"fr"' | '"cat"' | '"ft"' | '"fk"' | '"fq"' | '"fqa"' | '"fl"' | '"fw"' | '"fp"' | '"fdc"';

VAL_STYLE_XREF      : '"x"' | '"ex"';
VAL_STYLE_XREF_CHAR : '"xo"' | '"xop"' | '"xta"' | '"xk"' | '"xq"' | '"xot"' | '"xnt"' | '"xdc"';

VAL_STYLE_CHAPTER : '"c"';
VAL_STYLE_VERSE   : '"v"';
VAL_STYLE_SIDEBAR : '"esb"';

// --- Other Enums ---

VAL_ALIGN : '"start"' | '"center"' | '"end"';
VAL_BOOL  : '"true"' | '"false"';

VAL_BOOK_CODE : '"GEN"' | '"EXO"' | '"LEV"' | '"NUM"' | '"DEU"' | '"JOS"' | '"JDG"' | '"RUT"' | '"1SA"' | '"2SA"' | '"1KI"' | '"2KI"' | '"1CH"' | '"2CH"' | '"EZR"' | '"NEH"' | '"EST"' | '"JOB"' | '"PSA"' | '"PRO"' | '"ECC"' | '"SNG"' | '"ISA"' | '"JER"' | '"LAM"' | '"EZK"' | '"DAN"' | '"HOS"' | '"JOL"' | '"AMO"' | '"OBA"' | '"JON"' | '"MIC"' | '"NAM"' | '"HAB"' | '"ZEP"' | '"HAG"' | '"ZEC"' | '"MAL"' | '"MAT"' | '"MRK"' | '"LUK"' | '"JHN"' | '"ACT"' | '"ROM"' | '"1CO"' | '"2CO"' | '"GAL"' | '"EPH"' | '"PHP"' | '"COL"' | '"1TH"' | '"2TH"' | '"1TI"' | '"2TI"' | '"TIT"' | '"PHM"' | '"HEB"' | '"JAS"' | '"1PE"' | '"2PE"' | '"1JN"' | '"2JN"' | '"3JN"' | '"JUD"' | '"REV"' | '"TOB"' | '"JDT"' | '"ESG"' | '"WIS"' | '"SIR"' | '"BAR"' | '"LJE"' | '"S3Y"' | '"SUS"' | '"BEL"' | '"1MA"' | '"2MA"' | '"3MA"' | '"4MA"' | '"1ES"' | '"2ES"' | '"MAN"' | '"PS2"' | '"ODA"' | '"PSS"' | '"EZA"' | '"5EZ"' | '"6EZ"' | '"DAG"' | '"PS3"' | '"2BA"' | '"LBA"' | '"JUB"' | '"ENO"' | '"1MQ"' | '"2MQ"' | '"3MQ"' | '"REP"' | '"4BA"' | '"LAO"';

VAL_PERIPH_BOOK_CODE : '"CNC"' | '"GLO"' | '"TDX"' | '"NDX"';
VAL_PERIPH_DIV_CODE  : '"XXA"' | '"XXB"' | '"XXC"' | '"XXD"' | '"XXE"' | '"XXF"' | '"XXG"' | '"FRT"' | '"BAK"' | '"OTH"' | '"INT"';

VAL_PERIPH_ID : '"title"' | '"halftitle"' | '"promo"' | '"imprimatur"' | '"pubdata"' | '"foreword"' | '"preface"' | '"contents"' | '"alphacontents"' | '"abbreviations"' | '"intbible"' | '"intot"' | '"intpent"' | '"inthistory"' | '"intpoetry"' | '"intprophesy"' | '"intdc"' | '"intnt"' | '"intgospels"' | '"intepistles"' | '"intletters"' | '"chron"' | '"measures"' | '"maps"' | '"lxxquotes"' | '"cover"' | '"spine"';

STRING : '"' ~["]* '"';