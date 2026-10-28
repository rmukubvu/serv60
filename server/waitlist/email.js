/** Optional confirmation email through Resend's REST API. Sign-up never depends on it. */
import { createHash } from 'node:crypto';
import { escapeHtml } from './pages.js';

/** Returns null unless both the API key and a verified sender address are configured. */
export function mailerFromEnv(env = process.env, fetchImpl = globalThis.fetch) {
  if (!env.RESEND_API_KEY || !env.WAITLIST_FROM_EMAIL) return null;
  return {
    /** Resolves to true when Resend accepted the message; failures are logged without the address. */
    async sendConfirmation({ to, firstName, unsubscribeUrl }) {
      const greeting = firstName ? `Hi ${firstName},` : 'Hi there,';
      const lines = [
        'Thanks for joining the Serv60 waitlist.',
        'Serv60 is still in development: express car care, a fresh wash, good coffee, a barber studio and private workspaces in one place.',
        'We’ll email you when Serv60 opens, and waitlist members get early access to the Serv60 app first. We won’t use your details for anything else, and we won’t sell or share them.'
      ];
      const message = {
        from: env.WAITLIST_FROM_EMAIL,
        to: [to],
        subject: 'You’re on the Serv60 waitlist',
        text: [greeting, '', ...lines.flatMap(line => [line, '']), `Changed your mind? Unsubscribe and delete your details: ${unsubscribeUrl}`, '', 'Serv60', 'Car care. Time well spent.'].join('\n'),
        html: `<div style="font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.6;color:#111718;max-width:520px">
<p>${escapeHtml(greeting)}</p>${lines.map(line => `<p>${escapeHtml(line)}</p>`).join('')}
<p style="font-size:13px;color:#656b67">Changed your mind? <a href="${escapeHtml(unsubscribeUrl)}" style="color:#8a4a31">Unsubscribe and delete your details</a>.</p>
<p><strong>Serv60</strong><br>Car care. Time well spent.</p></div>`,
        // One-click unsubscribe (RFC 8058) lets mail apps offer their own unsubscribe button.
        headers: { 'List-Unsubscribe': `<${unsubscribeUrl}>`, 'List-Unsubscribe-Post': 'List-Unsubscribe=One-Click' }
      };
      if (env.WAITLIST_REPLY_TO) message.reply_to = env.WAITLIST_REPLY_TO;
      try {
        const response = await fetchImpl('https://api.resend.com/emails', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${env.RESEND_API_KEY}`,
            'Content-Type': 'application/json',
            // Resend drops a repeat within 24 hours, so a retried request cannot send twice.
            'Idempotency-Key': `waitlist-confirmation-${createHash('sha256').update(to).digest('hex').slice(0, 32)}`
          },
          body: JSON.stringify(message),
          signal: AbortSignal.timeout(6000)
        });
        if (!response.ok) console.error(`Waitlist confirmation email was not accepted: Resend responded with ${response.status}`);
        return response.ok;
      } catch (error) {
        console.error(`Waitlist confirmation email failed: ${error.name}`);
        return false;
      }
    }
  };
}
