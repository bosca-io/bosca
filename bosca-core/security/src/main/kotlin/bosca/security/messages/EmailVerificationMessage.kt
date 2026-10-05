package bosca.security.messages

import bosca.communications.service.MessageService

interface EmailVerificationTemplate : EmailTemplate

class EmailVerificationMessage(
    messages: MessageService,
    templates: EmailVerificationTemplate
) : AbstractMessage<EmailVerificationTemplate>(messages, templates)