# Business strategy and economics — assumptions, not forecasts

**Текущее указание владельца:** ценовые вопросы полностью отложены. Этот документ сохраняется как контекст полного видения; его примеры не являются утверждёнными тарифами, бюджетом или задачей текущего этапа. Возвращаться к ценовым расчётам по новому указанию владельца. Текущий план — [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).

## Positioning
Personal AI secretary with accountable memory, debts, income/expenses, tasks, calendar, people and proactive daily briefings. Competes with separate AI voice notes (e.g. Voicenotes), knowledge apps, task managers and budgeting tools. Validate competitor offerings and downloads periodically: store installations are cumulative thresholds, not active users or revenue.

## Revenue model
Freemium with a limited free voice/audio allowance and **one core paid tier** enabling the primary product features, plus fair usage quotas for costly services. Example hypothesis: $6.99–7.99/month with local currency tax handling. Annual billing possible without functional fragmentation. Do not promise unlimited live voice at a loss.

## Cost dimensions
STT billed by audio duration/size; LLM input/output/reasoning/cache tokens; embeddings; TTS and realtime voice; retry/fallback; object storage and transfer; database/search; push/operations; store commissions/tax; marketing, support and refunds. Actual cost measured per user and task type and sampled by language.

## Indicative model — not validated
Prior discussion estimated hybrid AI processing around $0.54 per active user/month with 180 audio minutes and local TTS. THIS IS ONLY A SCENARIO and is sensitive to languages, retry rate and vendor tariffs. Recalculate using actual provider billing, not an immutable financial truth.
If subscription is $6.99, hypothetical 15% distribution fee, $0.54 AI, $0.60 variable infra/support, $0.30 free-user burden and $0.75 churn replacement acquisition => contribution ~$3.75 per paying user before fixed cost, salaries, taxes, one-off engineering and additional marketing. With $800 fixed/month break-even roughly 214 paying users under these assumptions. This is an illustrative unit economics exercise, not expected profitability.

## Critical assumptions to validate
Demand, activation and retention, Android vs iOS conversion, free-user costs, paid price elasticity, CAC, churn, gross margin, average daily voice minutes, heavy-user distribution, refund/fraud, local payment availability, privacy trust. Paid-user counts cannot be inferred from Google Play download badges.

## Go-to-market
Focus on demonstrable scenarios: «Скажи: кто должен мне 300?», «Напомни обещание», «Голосом записывай доходы/расходы». Localize deeply RU/AZ/EN, then expand. Trust and accuracy in real life is stronger than generic AI-chat positioning. Product-led sharing must never reveal personal memory without active permission.

## Risks
Competition, accuracy on Azerbaijan mixed-language speech, provider price changes/outages, high audio usage and cloud realtime costs, limited user habit, store fees, consumer data/security rules, multi-tenant/privacy incidents. Control with testing, spend caps, transparent limits, independent backend, user research and careful launch.

## Success metrics
Cohort retention D1/D7/D30, time-to-first-value, critical task correctness, monthly paid active, conversion, ARPPU, net revenue retention, LTV:CAC, revenue after commissions, cost per processed minute, AI spend per active user, customer support burden, trust/complaints.
