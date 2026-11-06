# Planned Features

Features to make PennyWise a complete budget tracking replacement for spreadsheets.

## Already Implemented
- Transaction parsing from SMS (170+ banks, 26 countries)
- Budget creation with category limits, progress tracking, daily allowance
- Subscription detection and tracking
- AI chat for querying expenses
- Analytics with category breakdown
- Multi-currency support
- Split transactions
- Custom categorization rules
- What's New post-update dialog
- **System Prompt Additions (F8-T1)** — semantic blocks appended to system prompt, stored in DataStore, editable in Settings → Experimental (~200 tokens budget)
- **Custom HuggingFace Model (F8-T2)** — Experimental section with pre-download validation (HEAD request + SHA-256 pinning), state machine (IDLE→VALIDATED→READY), RAM gate integration, fallback to built-in Qwen
- **On-device AI Multimodal (Gemma 3n E2B)** — Vision (receipt photo → transaction), Audio (voice note → transcription → transaction)
- **Thinking Mode (Gemma 4 E2B, developer mode)** — Native chain-of-thought reasoning with configurable budget tokens
- **Expanded Tools (F4-T1..T4)** — `budget_status`, `category_breakdown`, `recent_transactions`, `account_balance`, `spending_forecast`
- **Mexico Bank Parsers** — BBVA México, AMEX México (with shared AMEX sender routing via Spanish content-gate)
- **Model Lifecycle** — SHA-256 streaming verification on every load, RAM gate, dynamic token budget per model
- **Savings Goals** — Named goals with target amount/deadline, linked to accounts, contributions/withdrawals linked to transactions
- **Split Bills / Loan Shares** — Shared expense tracking with UPI payment requests, per-person loan shares
- **Transaction Tags** — Free-text tags with many-to-many linking
- **Webhooks** — Configurable sync to external endpoints with ranges, data types, headers, retry logic
- **W0/W1 AI Contract Fixes** — INV-1/2/3 conversation invariants, anti-hallucination, narrated tool fallback

---

## Phase 1: Income & Full Picture

### Income Tracking
- Log income sources (salary, freelance, dividends, refunds)
- Detect income from SMS (salary credited, refund received)
- Monthly income vs expenses summary
- Savings rate calculation (Income - Expenses / Income)

### Monthly Overview
- Month-by-month grid/calendar view
- Each month shows: total income, total expenses, net savings
- Trend indicators (up/down vs previous month)
- Tap to drill into any month

---

## Phase 2: Goals & Forecasting

### Cash Flow Forecast
- Predict future balance based on recurring income/expenses
- "Will I have enough on the 25th?" view
- Weekly/monthly projection chart
- Alert when projected balance drops below threshold

---

## Phase 3: Flexibility & Export

### Data Export
- CSV export (full transaction history)
- PDF monthly statement
- Date range filtering for exports
- Share via email/apps

### Recurring Transaction Templates
- Manually add known recurring items (rent, EMI, insurance)
- Auto-create expected transactions each period
- Mark as paid when SMS arrives
- Forecast upcoming bills

---

## Phase 4: Advanced

### Family/Shared Budgets
- Invite family members
- Shared budget pools
- Individual vs shared expense categorization
- Per-member spending breakdown

### Net Worth Tracking
- Add assets (savings, investments, property)
- Track liabilities (loans, credit cards)
- Net worth over time chart
- Manual or linked account balances