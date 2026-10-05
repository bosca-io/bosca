parser grammar USXParser;

options { tokenVocab=USXLexer; }

// ============================================================
// ROOT
// ============================================================

start
    : usx EOF
    ;

usx
    : openUsx
      (scripture | peripheral)
      closeUsx
    ;

openUsx
    : START_TAG_OPEN KW_USX usxAttributes TAG_CLOSE
    ;

closeUsx
    : END_TAG_OPEN KW_USX TAG_CLOSE
    ;

usxAttributes
    : (attrVersion | attrNoNamespaceSchemaLocation)*
    ;

// ============================================================
// STRUCTURES
// ============================================================

scripture
    : bookIdentification
      bookHeaders*
      bookTitles+
      bookIntroduction*
      bookIntroductionEndTitles*
      bookChapterLabel?
      malformedChapter?
      chapter*
    ;

peripheral
    : openUsx (peripheralBook | peripheralDividedBook) closeUsx
    ;

peripheralBook
    : peripheralBookIdentification
      bookHeaders*
      bookTitles+
      bookIntroduction*
      bookIntroductionEndTitles*
      peripheralContent+
    ;

peripheralDividedBook
    : peripheralDividedBookIdentification
      (peripheralDivision | peripheralOther)
    ;

// --- Book ID ---

bookIdentification
    : START_TAG_OPEN KW_BOOK bookIdentAttrs (TAG_CLOSE text? closeBook | TAG_SLASH_CLOSE)
    ;

bookIdentAttrs
    : (attrCodeBook | attrStyleId)+
    ;

peripheralBookIdentification
    : START_TAG_OPEN KW_BOOK periphBookIdentAttrs (TAG_CLOSE text? closeBook | TAG_SLASH_CLOSE)
    ;

periphBookIdentAttrs
    : (attrCodePeriphBook | attrStyleId)+
    ;

peripheralDividedBookIdentification
    : START_TAG_OPEN KW_BOOK periphDivBookIdentAttrs (TAG_CLOSE text? closeBook | TAG_SLASH_CLOSE)
    ;

periphDivBookIdentAttrs
    : (attrCodePeriphDivBook | attrStyleId)+
    ;

closeBook
    : END_TAG_OPEN KW_BOOK TAG_CLOSE
    ;

// --- Peripheral Divisions ---

peripheralDivision
    : START_TAG_OPEN KW_PERIPH periphDivAttrs TAG_CLOSE
      bookHeaders*
      bookTitles+
      bookIntroduction*
      bookIntroductionEndTitles*
      peripheralContent*
      closePeriph
    ;

periphDivAttrs
    : (attrIdPeriph | attrAlt)*
    ;

peripheralOther
    : bookHeaders*
      bookTitles*
      bookIntroduction*
      bookIntroductionEndTitles*
      peripheralContent*
    ;

closePeriph
    : END_TAG_OPEN KW_PERIPH TAG_CLOSE
    ;

// ============================================================
// HEADERS & INTROS
// ============================================================

bookHeaders
    : START_TAG_OPEN KW_PARA attrStyleHeader (TAG_CLOSE text? closePara | slashClose)
    ;

bookTitles
    : START_TAG_OPEN KW_PARA attrStyleTitle
      ( TAG_CLOSE (footnote | crossReference | charElement | breakElement | text)* closePara
      | slashClose
      )
    ;

slashClose
    : TAG_SLASH_CLOSE
    ;

bookIntroduction
    : START_TAG_OPEN KW_PARA bookIntroAttrs
      ( TAG_CLOSE (refElement | footnote | crossReference | charElement | introChar | milestone | figure | text)+ closePara
      | slashClose
      )
    | table
    ;

bookIntroAttrs
    : (attrStyleIntro | attrVid)+
    ;

bookIntroductionEndTitles
    : START_TAG_OPEN KW_PARA attrStyleIntroEnd
      ( TAG_CLOSE (footnote | crossReference | charElement | milestone | breakElement | text)* closePara
      | slashClose
      )
    ;

bookChapterLabel
    : START_TAG_OPEN KW_PARA attrStyleChapterLabel (TAG_CLOSE text closePara | slashClose)
    ;

// ============================================================
// CONTENT BLOCKS
// ============================================================

malformedChapter
    : para chapterContent+ chapterEnd
    ;

chapterContent
    : para | list | table | footnote | crossReference | sidebar
    ;

peripheralContent
    : chapter | para | list | table | footnote | crossReference | sidebar
    ;

// MODIFIED: Allowed self-closing paras (e.g., <para style="b"/>)
para
    : START_TAG_OPEN KW_PARA paraAttrs
      ( TAG_CLOSE paraContent closePara
      | slashClose
      )
    ;

paraContent
    : (refElement | footnote | crossReference | charElement | milestone | figure | verse | breakElement | text)+
    ;

paraAttrs
    : (attrStylePara | attrVid)*
    ;

closePara
    : END_TAG_OPEN KW_PARA TAG_CLOSE
    ;

// MODIFIED: Allowed self-closing lists
list
    : START_TAG_OPEN KW_PARA listAttrs
      ( TAG_CLOSE (refElement | footnote | crossReference | charElement | listChar | milestone | figure | verse | breakElement | text)+ closePara
      | slashClose
      )
    ;

listAttrs
    : (attrStyleList | attrVid)*
    ;

table
    : START_TAG_OPEN KW_TABLE attrVid? TAG_CLOSE row+ closeTable
    ;

closeTable
    : END_TAG_OPEN KW_TABLE TAG_CLOSE
    ;

row
    : START_TAG_OPEN KW_ROW attrStyleRow TAG_CLOSE (verse | tableContent)+ closeRow
    ;

closeRow
    : END_TAG_OPEN KW_ROW TAG_CLOSE
    ;

tableContent
    : START_TAG_OPEN KW_CELL cellAttrs
      ( TAG_CLOSE (footnote | crossReference | charElement | milestone | figure | verse | breakElement | text)* closeCell
      | slashClose
      )
    ;

closeCell
    : END_TAG_OPEN KW_CELL TAG_CLOSE
    ;

cellAttrs
    : (attrStyleCell | attrAlign | attrColspan)*
    ;

// ============================================================
// INLINE ELEMENTS
// ============================================================

charElement
    : START_TAG_OPEN KW_CHAR charAttrs
      ( TAG_CLOSE (refElement | charElement | milestone | footnote | breakElement | text)* closeChar
      | slashClose
      )
    | charWithAttrib
    ;

charAttrs
    : (attrStyleChar | attrLinkHref | attrLinkTitle | attrLinkId | attrClosed | otherAttribute)*
    ;

charWithAttrib
    : charStyleW
    | charStyleRb
    ;

charStyleW
    : START_TAG_OPEN KW_CHAR charStyleWAttrs
      ( TAG_CLOSE (refElement | charElement | milestone | footnote | breakElement | text)* closeChar
      | slashClose
      )
    ;

charStyleWAttrs
    : (attrStyleW | attrLinkHref | attrClosed | attrLemma | attrStrong | attrSrcLoc | otherAttribute)*
    ;

charStyleRb
    : START_TAG_OPEN KW_CHAR charStyleRbAttrs
      ( TAG_CLOSE (refElement | charElement | milestone | footnote | breakElement | text)* closeChar
      | slashClose
      )
    ;

charStyleRbAttrs
    : (attrStyleRb | attrLinkHref | attrClosed | attrGloss | otherAttribute)*
    ;

introChar
    : START_TAG_OPEN KW_CHAR introCharAttrs
      ( TAG_CLOSE (refElement | charElement | introChar | milestone | footnote | breakElement | text)* closeChar
      | slashClose
      )
    ;

introCharAttrs
    : (attrStyleIntroChar | attrClosed | otherAttribute)*
    ;

listChar
    : START_TAG_OPEN KW_CHAR listCharAttrs
      ( TAG_CLOSE (refElement | charElement | milestone | footnote | breakElement | text)* closeChar
      | slashClose
      )
    ;

listCharAttrs
    : (attrStyleListChar | attrLinkHref | attrClosed | otherAttribute)*
    ;

closeChar
    : END_TAG_OPEN KW_CHAR TAG_CLOSE
    ;

milestone
    : START_TAG_OPEN KW_MS milestoneAttrs (TAG_SLASH_CLOSE | TAG_CLOSE)
    ;

milestoneAttrs
    : (attrStyleMilestone | attrSid | attrEid | attrWho)*
    ;

figure
    : START_TAG_OPEN KW_FIGURE figureAttrs (TAG_CLOSE text? closeFigure | TAG_SLASH_CLOSE)
    ;

figureAttrs
    : (attrStyleGeneric | attrAlt | attrFile | attrSize | attrLoc | attrCopy | attrRef)*
    ;

closeFigure
    : END_TAG_OPEN KW_FIGURE TAG_CLOSE
    ;

breakElement
    : START_TAG_OPEN KW_OPTBREAK TAG_SLASH_CLOSE
    ;

// ============================================================
// CHAPTERS & VERSES
// ============================================================

chapter
    : chapterStart chapterContent+ chapterEnd
    ;

chapterStart
    : START_TAG_OPEN KW_CHAPTER chapterStartAttrs TAG_SLASH_CLOSE
    ;

chapterStartAttrs
    : (attrNumber | attrStyleChapter | attrSid | attrAltNumber | attrPubNumber)*
    ;

chapterEnd
    : START_TAG_OPEN KW_CHAPTER attrEid TAG_SLASH_CLOSE
    ;

verse
    : verseStart
    | verseEnd
    ;

verseStart
    : START_TAG_OPEN KW_VERSE verseStartAttrs TAG_SLASH_CLOSE
    ;

verseStartAttrs
    : (attrNumberString | attrStyleVerse | attrAltNumber | attrPubNumber | attrSid)*
    ;

verseEnd
    : START_TAG_OPEN KW_VERSE attrEid TAG_SLASH_CLOSE
    ;

// ============================================================
// NOTES & REFS
// ============================================================

footnote
    : START_TAG_OPEN KW_NOTE footnoteAttrs TAG_CLOSE (footnoteChar | text)+ closeNote
    ;

footnoteAttrs
    : (attrStyleFootnote | attrCaller | attrCategory)*
    ;

closeNote
    : END_TAG_OPEN KW_NOTE TAG_CLOSE
    ;

footnoteChar
    : START_TAG_OPEN KW_CHAR footnoteCharAttrs
      ( TAG_CLOSE (charElement | footnoteChar | footnoteVerse | refElement | text)+ closeChar
      | slashClose
      )
    ;

footnoteCharAttrs
    : (attrStyleFootnoteChar | attrLinkHref | attrClosed)*
    ;

footnoteVerse
    : START_TAG_OPEN KW_CHAR attrStyleFv TAG_CLOSE text closeChar
    ;

crossReference
    : START_TAG_OPEN KW_NOTE crossRefAttrs TAG_CLOSE (crossRefChar | text)+ closeNote
    ;

crossRefAttrs
    : (attrStyleCrossRef | attrCaller)*
    ;

crossRefChar
    : START_TAG_OPEN KW_CHAR crossRefCharAttrs
      ( TAG_CLOSE (charElement | crossRefChar | refElement | text)+ closeChar
      | slashClose
      )
    ;

crossRefCharAttrs
    : (attrStyleCrossRefChar | attrLinkHref | attrClosed)*
    ;

sidebar
    : START_TAG_OPEN KW_SIDEBAR sidebarAttrs TAG_CLOSE (para | list | table | footnote | crossReference)+ closeSidebar
    ;

sidebarAttrs
    : (attrStyleSidebar | attrCategory)*
    ;

closeSidebar
    : END_TAG_OPEN KW_SIDEBAR TAG_CLOSE
    ;

refElement
    : START_TAG_OPEN KW_REF attrLoc (TAG_CLOSE text? closeRef | TAG_SLASH_CLOSE)
    ;

closeRef
    : END_TAG_OPEN KW_REF TAG_CLOSE
    ;

text
    : TEXT
    ;

// ============================================================
// ATTRIBUTE RULES
// ============================================================

attrVersion         : KW_VERSION EQ STRING;
attrNoNamespaceSchemaLocation : KW_XSI_SCHEMA EQ STRING;

attrCodeBook        : KW_CODE EQ VAL_BOOK_CODE;
attrCodePeriphBook  : KW_CODE EQ VAL_PERIPH_BOOK_CODE;
attrCodePeriphDivBook : KW_CODE EQ VAL_PERIPH_DIV_CODE;

attrStyleId         : KW_STYLE EQ VAL_ID;

attrStyleHeader     : KW_STYLE EQ (VAL_STYLE_HEADER | VAL_REM | VAL_USFM);
attrStyleTitle      : KW_STYLE EQ (VAL_MT_ALL | VAL_IMT_ALL | VAL_REM);

attrStyleIntro      : KW_STYLE EQ (VAL_STYLE_INTRO | VAL_REM | VAL_MT_ALL | VAL_IMT_ALL | VAL_STYLE_PARA);

attrStyleIntroEnd   : KW_STYLE EQ (VAL_MT_ALL | VAL_IMT_ALL);

attrStyleChapterLabel : KW_STYLE EQ VAL_CL;

attrStylePara       : KW_STYLE EQ (VAL_STYLE_PARA | VAL_REM | VAL_CL | VAL_STYLE_INTRO);

attrStyleList       : KW_STYLE EQ VAL_STYLE_LIST;
attrStyleRow        : KW_STYLE EQ VAL_STYLE_TABLE;
attrStyleCell       : KW_STYLE EQ VAL_STYLE_CELL;

attrStyleIntroChar  : KW_STYLE EQ VAL_STYLE_INTRO_CHAR;

attrStyleChar       : KW_STYLE EQ (VAL_STYLE_CHAR | VAL_FORMAT | VAL_XT);

attrStyleW          : KW_STYLE EQ VAL_STYLE_W;
attrStyleRb         : KW_STYLE EQ VAL_STYLE_RB;
attrStyleListChar   : KW_STYLE EQ VAL_STYLE_LIST_CHAR;
attrStyleMilestone  : KW_STYLE EQ STRING;
attrStyleChapter    : KW_STYLE EQ VAL_STYLE_CHAPTER;
attrStyleVerse      : KW_STYLE EQ VAL_STYLE_VERSE;
attrStyleFootnote   : KW_STYLE EQ VAL_STYLE_NOTE;

// Use VAL_FV directly here to fix implicit definition error
attrStyleFootnoteChar : KW_STYLE EQ (VAL_STYLE_NOTE_CHAR | VAL_FV | VAL_XT | VAL_FORMAT);
attrStyleFv         : KW_STYLE EQ VAL_FV;

attrStyleCrossRef   : KW_STYLE EQ VAL_STYLE_XREF;
attrStyleCrossRefChar : KW_STYLE EQ (VAL_STYLE_XREF_CHAR | VAL_XT);

attrStyleSidebar    : KW_STYLE EQ VAL_STYLE_SIDEBAR;
attrStyleGeneric    : KW_STYLE EQ STRING;

attrVid      : KW_VID EQ STRING;
attrIdPeriph : KW_ID EQ (VAL_PERIPH_ID | STRING);
attrAlt      : KW_ALT EQ STRING;
attrAlign    : KW_ALIGN EQ VAL_ALIGN;
attrColspan  : KW_COLSPAN EQ STRING;
attrClosed   : KW_CLOSED EQ VAL_BOOL;
attrLinkHref : KW_LINK_HREF EQ STRING;
attrLinkTitle: KW_LINK_TITLE EQ STRING;
attrLinkId   : KW_LINK_ID EQ STRING;
attrLemma    : KW_LEMMA EQ STRING;
attrStrong   : KW_STRONG EQ STRING;
attrSrcLoc   : KW_SRCLOC EQ STRING;
attrGloss    : KW_GLOSS EQ STRING;
attrSid      : KW_SID EQ STRING;
attrEid      : KW_EID EQ STRING;
attrWho      : KW_WHO EQ STRING;
attrNumber   : KW_NUMBER EQ STRING;
attrNumberString : KW_NUMBER EQ STRING;
attrAltNumber: KW_ALTNUMBER EQ STRING;
attrPubNumber: KW_PUBNUMBER EQ STRING;
attrCaller   : KW_CALLER EQ STRING;
attrCategory : KW_CATEGORY EQ STRING;
attrFile     : KW_FILE EQ STRING;
attrSize     : KW_SIZE EQ STRING;
attrLoc      : KW_LOC EQ STRING;
attrCopy     : KW_COPY EQ STRING;
attrRef      : KW_REF EQ STRING;

otherAttribute : STRING EQ STRING;