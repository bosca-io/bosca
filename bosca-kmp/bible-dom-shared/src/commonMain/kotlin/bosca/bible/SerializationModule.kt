package bosca.bible

import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.bible.style.IStyle
import bosca.bible.style.Style
import bosca.bible.style.StyleReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

val BibleSerializers = SerializersModule {
    polymorphic(IStyle::class) {
        subclass(Style::class, Style.serializer())
        subclass(StyleReference::class, StyleReference.serializer())
    }
    polymorphic(IComponent::class) {
        subclass(VerseStart::class, VerseStart.serializer())
        subclass(VerseEnd::class, VerseEnd.serializer())
        subclass(Text::class, Text.serializer())
    }
}

val bibleJson = Json {
    serializersModule = BibleSerializers
    classDiscriminator = "_"
}
