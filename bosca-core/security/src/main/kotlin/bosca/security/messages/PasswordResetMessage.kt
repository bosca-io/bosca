package bosca.security.messages

import bosca.communications.service.MessageService

interface EmailPasswordResetTemplate : EmailTemplate

class PasswordResetMessage(
    messages: MessageService,
    templates: EmailPasswordResetTemplate
) : AbstractMessage<EmailPasswordResetTemplate>(messages, templates)