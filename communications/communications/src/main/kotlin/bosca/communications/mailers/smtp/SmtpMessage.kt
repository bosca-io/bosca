package bosca.communications.mailers.smtp

import bosca.communications.mailers.ContentType
import bosca.communications.mailers.EmailMessage
import bosca.communications.mailers.InlineImage
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Base64
import java.util.Date

/** A message delivered separately to each recipient, keeping recipient addresses private. */
data class SmtpMessage(
    override val from: SmtpEmail,
    override val to: List<SmtpEmail>,
    override val subject: String,
    override val content: List<SmtpContent>,
    val inlineImages: List<InlineImage> = emptyList(),
) : EmailMessage {
    internal fun toMimeMessage(session: Session, recipient: SmtpEmail): MimeMessage {
        require(subject.none { it == '\r' || it == '\n' }) { "SMTP subject cannot contain line breaks" }
        require(content.isNotEmpty()) { "SMTP message must have content" }
        val alternatives = MimeMultipart("alternative")
        content.sortedBy { it.type.ordinal }.forEach { body ->
            alternatives.addBodyPart(MimeBodyPart().apply {
                setText(body.content, "UTF-8", if (body.type == ContentType.HTML) "html" else "plain")
            })
        }
        val body = if (inlineImages.isEmpty()) alternatives else MimeMultipart("related").apply {
            addBodyPart(MimeBodyPart().apply { setContent(alternatives) })
            inlineImages.forEach { image ->
                require(listOf(image.cid, image.filename, image.mediaType).none { '\r' in it || '\n' in it }) {
                    "SMTP image headers cannot contain line breaks"
                }
                addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(Base64.getDecoder().decode(image.contentBase64), image.mediaType))
                    disposition = Part.INLINE
                    fileName = image.filename
                    setHeader("Content-ID", "<${image.cid}>")
                })
            }
        }
        return MimeMessage(session).apply {
            setFrom(this@SmtpMessage.from.address())
            setRecipient(Message.RecipientType.TO, recipient.address())
            setSubject(this@SmtpMessage.subject, "UTF-8")
            sentDate = Date()
            setContent(body)
            saveChanges()
        }
    }
}
