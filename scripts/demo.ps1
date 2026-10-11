# Demo walkthrough for Windows PowerShell 5.1+ (no extra tools needed).
# Start the API first, then:
#   powershell -ExecutionPolicy Bypass -File scripts\demo.ps1
# Each section prints a caption explaining what is about to happen, then waits for Enter.
# -NoPause runs straight through.
param(
    [string]$Api = "http://localhost:8080",
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Admin = 1
$Stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

function Pause-Here {
    if (-not $NoPause) { [void](Read-Host "  (press Enter to continue)") }
}

function Step([string]$Title, [string[]]$Caption) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    foreach ($line in $Caption) { Write-Host "  $line" -ForegroundColor White }
    Write-Host ""
    Pause-Here
}

function Note([string]$Text) {
    Write-Host "  $Text" -ForegroundColor Yellow
}

# One HTTP call. Returns status, the Idempotent-Replayed header and the parsed JSON body (also for 4xx).
function Call([string]$Method, [string]$Path, $User, $Body, [string]$Key) {
    $headers = @{}
    if ($User) { $headers['X-User-Id'] = "$User" }
    if ($Method -eq 'POST') {
        if (-not $Key) { $Key = [guid]::NewGuid().ToString() }
        $headers['Idempotency-Key'] = $Key
    }
    $params = @{ Uri = "$Api$Path"; Method = $Method; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        $status = [int]$r.StatusCode
        $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        $replayed = $r.Headers['Idempotent-Replayed']
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { throw }
        $status = [int]$resp.StatusCode
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $text = $reader.ReadToEnd()
        $replayed = $resp.Headers['Idempotent-Replayed']
    }
    $json = $null
    if ($text) { $json = $text | ConvertFrom-Json }
    [pscustomobject]@{ Status = $status; Replayed = $replayed; Json = $json }
}

function Show($Value) {
    Write-Host (($Value | ConvertTo-Json -Depth 10) -replace '(?m)^', '  ')
}

function Rupees([long]$Paise) {
    return ('Rs. {0:N2}' -f ($Paise / 100.0))
}

function Balance([long]$Wallet, [long]$User) {
    return (Call GET "/wallets/$Wallet/balance" $User).Json.balance
}

# ---------------------------------------------------------------------------------------------
Step "PAYMENTS-GRADE WALLET LEDGER - demo" @(
    "Spring Boot + PostgreSQL. Double-entry: every rupee is written as a debit and a matching credit.",
    "Money never appears from nowhere, disappears, or moves twice.",
    "",
    "  1. One transfer end to end      2. A client retry (idempotency)",
    "  3. A transfer held by a fraud rule, then released by an admin",
    "  4. A reconciliation run         5. A plain-English statement question")

Step "SETUP - two users, a wallet each, Asha tops up Rs. 1,000" @(
    "Create users Asha and Rahul and open a wallet for each.",
    "Asha tops up Rs. 1,000: a TOP_UP transfer from the system CASH_IN account to her wallet.")
$asha = (Call POST "/users" $null @{ name = "Asha Verma"; email = "asha$Stamp@demo.in"; phone = "+9198$($Stamp.ToString().Substring(2))" }).Json.user_id
$rahul = (Call POST "/users" $null @{ name = "Rahul Mehta"; email = "rahul$Stamp@demo.in"; phone = "+9197$($Stamp.ToString().Substring(2))" }).Json.user_id
foreach ($u in @($asha, $rahul)) { [void](Call POST "/admin/users/$u/kyc" $Admin @{ kyc_status = "VERIFIED" }) }
$ashaWallet = (Call POST "/users/$asha/wallets" $asha @{ name = "main" }).Json.wallet_id
$rahulWallet = (Call POST "/users/$rahul/wallets" $rahul @{ name = "main" }).Json.wallet_id
$top = Call POST "/transfers/topup" $asha @{ wallet_id = $ashaWallet; amount = 100000 }
Note "Asha = user $asha, wallet $ashaWallet      Rahul = user $rahul, wallet $rahulWallet"
Note "Top-up -> HTTP $($top.Status), transfer $($top.Json.transfer_id), $($top.Json.type) $($top.Json.status)"
Note "Asha's balance: $(Rupees (Balance $ashaWallet $asha))"

# ---------------------------------------------------------------------------------------------
Pause-Here
Step "1. ONE TRANSFER END TO END - Asha sends Rahul Rs. 250" @(
    "POST /transfers. Inside ONE database transaction the server:",
    "  - locks both wallet rows with SELECT ... FOR UPDATE, lowest id first (no deadlocks)",
    "  - re-checks Asha's balance while holding the lock",
    "  - writes the transfer, a DEBIT on Asha's wallet and an equal CREDIT on Rahul's",
    "  - updates both balances, then commits. Any failure rolls everything back.")
$t = Call POST "/transfers" $asha @{ from_wallet_id = $ashaWallet; to_wallet_id = $rahulWallet; amount = 25000; note = "Dinner" }
Note "HTTP $($t.Status)"
Show ([pscustomobject]@{
    transfer_id    = $t.Json.transfer_id
    type           = $t.Json.type
    status         = $t.Json.status
    amount_paise   = $t.Json.amount
    ledger_entries = @($t.Json.entries | ForEach-Object { "$($_.direction) account $($_.account_id) $($_.amount)" })
    status_history = @($t.Json.status_history | ForEach-Object { "$($_.to_status) by $($_.changed_by)" })
})
Note "Debit on Asha's wallet, matching credit on Rahul's: the transfer balances."
Note "Asha's balance: $(Rupees (Balance $ashaWallet $asha))     Rahul's balance: $(Rupees (Balance $rahulWallet $rahul))"
$st = (Call GET "/wallets/$rahulWallet/statement?limit=3" $rahul).Json
Note "Rahul's statement, newest first:"
$st.entries | ForEach-Object { Write-Host ("    entry {0}  {1} {2,8}  from {3}  '{4}'" -f $_.entry_id, $_.direction, $_.amount, $_.counterparty_name, $_.note) }

# ---------------------------------------------------------------------------------------------
Pause-Here
Step "2. A RETRY - the same request sent 3 times with ONE Idempotency-Key" @(
    "A payment can succeed on the server while the response is lost, so the client retries.",
    "Every POST carries an Idempotency-Key. Same key + same body -> the saved response is replayed.",
    "Same key + different body -> 422. Expected: one transfer, Rs. 50 debited once.")
$key = [guid]::NewGuid().ToString()
Note "Idempotency-Key: $key"
for ($i = 1; $i -le 3; $i++) {
    $r = Call POST "/transfers" $asha @{ from_wallet_id = $ashaWallet; to_wallet_id = $rahulWallet; amount = 5000 } $key
    Note "Attempt $i -> HTTP $($r.Status), transfer_id $($r.Json.transfer_id), Idempotent-Replayed: $($r.Replayed)"
}
Note "Asha's balance: $(Rupees (Balance $ashaWallet $asha))   (Rs. 50 left her wallet once, not three times)"
$bad = Call POST "/transfers" $asha @{ from_wallet_id = $ashaWallet; to_wallet_id = $rahulWallet; amount = 9999 } $key
Note "Same key, DIFFERENT amount -> HTTP $($bad.Status) $($bad.Json.code): $($bad.Json.message)"
Note "Asha's balance: $(Rupees (Balance $ashaWallet $asha))   (unchanged - no money moved)"

# ---------------------------------------------------------------------------------------------
Pause-Here
Step "3. A HELD TRANSFER - Rs. 60,000 trips the LARGE_AMOUNT fraud rule" @(
    "Fraud rules (thresholds stored in the risk_rules table) run inside the transfer transaction.",
    "LARGE_AMOUNT holds anything >= Rs. 50,000: status HELD, HTTP 202, and NO money moves.",
    "The LLM writes a short explanation for the reviewer; the rule, not the LLM, makes the decision.",
    "An admin then RELEASEs it: the balance is re-checked under lock and the entries are posted.")
[void](Call POST "/transfers/topup" $asha @{ wallet_id = $ashaWallet; amount = 7000000 })
Note "Asha topped up Rs. 70,000. Balance: $(Rupees (Balance $ashaWallet $asha))"
$h = Call POST "/transfers" $asha @{ from_wallet_id = $ashaWallet; to_wallet_id = $rahulWallet; amount = 6000000; note = "Car payment" }
$hid = $h.Json.transfer_id
Note "HTTP $($h.Status) -> transfer $hid is $($h.Json.status)"
Note "Rules that fired: $((@($h.Json.risk_flags | ForEach-Object { "$($_.rule) ($($_.action))" })) -join ', ')"
Note "Ledger entries: $(@($h.Json.entries).Count)   Asha's balance: $(Rupees (Balance $ashaWallet $asha))   (no money moved)"
Write-Host ""
$queue = (Call GET "/admin/risk/queue?limit=1" $Admin).Json
Note "Admin risk queue, first item: transfer $($queue[0].transfer_id), $($queue[0].status), $(Rupees $queue[0].amount)"
$ex = (Call POST "/admin/risk/explain" $Admin @{}).Json
Note "LLM explanations written: $($ex.explained)"
$flag = (Call GET "/transfers/$hid" $Admin).Json.risk_flags[0]
Note "Explanation for $($flag.rule): [$($flag.llm_label)] $($flag.llm_explanation)"
Write-Host ""
$rel = Call POST "/admin/risk/$hid/decision" $Admin @{ decision = "RELEASE"; reason = "Confirmed with the customer by phone" }
Note "Admin RELEASE -> HTTP $($rel.Status), status $($rel.Json.status), ledger entries: $(@($rel.Json.entries).Count)"
$rel.Json.status_history | ForEach-Object { Write-Host ("    {0,-8} -> {1,-8} by {2,-8} {3}" -f $_.from_status, $_.to_status, $_.changed_by, $_.reason) }
Note "Asha: $(Rupees (Balance $ashaWallet $asha))     Rahul: $(Rupees (Balance $rahulWallet $rahul))"

# ---------------------------------------------------------------------------------------------
Pause-Here
Step "4. A RECONCILIATION RUN - prove the books balance" @(
    "The nightly job, triggered manually (POST /admin/reconciliation/run -> 202, then poll the run).",
    "Five SQL checks in ONE REPEATABLE READ snapshot: total debits = credits, every transfer balances,",
    "stored balances match the ledger, no negative wallets, nothing orphaned.",
    "SELECT ... FOR UPDATE NOWAIT on a job_locks row guarantees only one run at a time.")
$run = Call POST "/admin/reconciliation/run" $Admin @{}
Note "HTTP $($run.Status): run $($run.Json.run_id) is $($run.Json.status)"
do {
    Start-Sleep -Milliseconds 500
    $report = (Call GET "/admin/reconciliation/runs/$($run.Json.run_id)" $Admin).Json
} while ($report.status -eq 'RUNNING')
Note "Run $($report.run_id): $($report.status), problems found: $($report.problem_count)"
Show ([pscustomobject]@{
    total_debits                 = $report.stats.total_debits
    total_credits                = $report.stats.total_credits
    unbalanced_transfers         = $report.stats.check2_unbalanced_transfers
    stored_balance_mismatches    = $report.stats.check3_stored_balance_mismatches
    negative_wallets             = $report.stats.check4_negative_wallets
    posted_transfers_no_entries  = $report.stats.check5_posted_without_entries
    entries_without_transfer     = $report.stats.check5_entries_without_transfer
})

# ---------------------------------------------------------------------------------------------
Pause-Here
Step "5. A STATEMENT QUESTION IN PLAIN ENGLISH" @(
    "POST /users/{id}/statement/ask. The LLM only converts the question into JSON",
    "(type, counterparty, date range). The code validates it and runs its own parameterised",
    "query, always limited to the caller's wallets. The LLM never writes SQL.")
foreach ($q in @("How much did I send to Rahul this month?", "What is the weather in Mumbai?")) {
    $a = (Call POST "/users/$asha/statement/ask" $asha @{ question = $q }).Json
    Note "Q: $q"
    Write-Host "  A: $($a.answer)" -ForegroundColor Green
    if ($a.supported) { Note "   from transfers: $(@($a.transfer_ids) -join ', ')" }
    Write-Host ""
}
Note "The LLM only turns the question into JSON; the number comes from a parameterised query on Asha's own rows."
Pause-Here
Write-Host ""
Write-Host ("=" * 78) -ForegroundColor DarkGray
Write-Host "  DEMO COMPLETE" -ForegroundColor Cyan
Write-Host ("=" * 78) -ForegroundColor DarkGray
Write-Host "  Also covered by automated tests, passing in CI:" -ForegroundColor White
Write-Host "    - stampede: 100 concurrent Rs. 10 transfers from Rs. 500 -> exactly 50 succeed (x20 runs)" -ForegroundColor White
Write-Host "    - idempotency: 10 parallel retries with one key -> exactly 1 transfer" -ForegroundColor White
Write-Host "    - reconciliation: 3 deliberate corruptions in 10,000 transfers -> exactly 3 problems reported" -ForegroundColor White
Write-Host "    - prompt injection: a malicious transfer note cannot widen the answer beyond the caller's data" -ForegroundColor White
Write-Host ""
