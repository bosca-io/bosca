package bosca.security.messages

import bosca.communications.service.MessageService

interface EmailForgotPasswordTemplate : EmailTemplate

class ForgotPasswordMessage(
    messages: MessageService,
    templates: EmailForgotPasswordTemplate
) : AbstractMessage<EmailForgotPasswordTemplate>(messages, templates)