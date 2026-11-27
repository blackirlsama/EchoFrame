param(
    [ValidateSet("SyntheticPair", "AStopPair", "CanonPair", "Contract", "ReportArguments", "Ledger", "Fee", "Approval", "Stop", "CanonStop")]
    [string]$Mode = "SyntheticPair",
    [string]$EvidenceRoot,
    [string]$Ledger,
    [string]$LedgerSha256,
    [string]$MaterialConfig,
    [string]$FramesSource,
    [string]$ApprovalFile,
    [string]$ContractFile,
    [string]$ContractOut,
    [string]$ApprovalBaseline,
    [string]$ApprovalGroup = "canon-730013-pair",
    [string]$ApprovalBudget = "headroom-770",
    [string]$AnalysisModel = "deepseek-ai/DeepSeek-V4.1-Flash",
    [switch]$Offline,
    [string]$Mvn = "mvn"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$repoFull = ([System.IO.Path]::GetFullPath($repo)).TrimEnd('\')
$agent = Join-Path $repo "agent"

function FullPath([string]$path) {
    return [System.IO.Path]::GetFullPath($path)
}

function Normalize([string]$path) {
    return (FullPath $path).Replace('\', '/')
}

function Get-Sha256File([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Sha256Text([string]$text) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally { $sha.Dispose() }
}

function Assert-NoReparse([string]$path) {
    $cursor = FullPath $path
    while ($cursor -and -not (Test-Path -LiteralPath $cursor)) {
        $cursor = Split-Path -Parent $cursor
    }
    while ($cursor) {
        $item = Get-Item -LiteralPath $cursor -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "REPARSE_POINT_REFUSED: $cursor"
        }
        $cursor = Split-Path -Parent $cursor
    }
}

function Assert-InputFile([string]$path, [string]$label) {
    if (-not $path) { throw "$label is required" }
    $full = FullPath $path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "$label missing: $full" }
    Assert-NoReparse $full
    return $full
}

function Assert-InputDir([string]$path, [string]$label) {
    if (-not $path) { throw "$label is required" }
    $full = FullPath $path
    if (-not (Test-Path -LiteralPath $full -PathType Container)) { throw "$label missing: $full" }
    Assert-NoReparse $full
    return $full
}

function Assert-NotOverlap([string]$root, [string]$candidate, [string]$label) {
    $r = (FullPath $root).TrimEnd('\')
    $i = (FullPath $candidate).TrimEnd('\')
    if ($r.Equals($i, [StringComparison]::OrdinalIgnoreCase) `
        -or $r.StartsWith($i + '\', [StringComparison]::OrdinalIgnoreCase) `
        -or $i.StartsWith($r + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "EVIDENCE_INPUT_OVERLAP: evidence root and $label overlap: $r <-> $i"
    }
}

function Assert-EvidenceRoot([string]$root) {
    $full = FullPath $root
    Assert-NoReparse $full
    if (-not $full.StartsWith($repoFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "EVIDENCE_ROOT_OUTSIDE_REPO: $full must stay inside $repoFull"
    }
    if (Test-Path -LiteralPath $full) {
        throw "EVIDENCE_ROOT_OCCUPIED: $full already exists; choose a new root"
    }
    return $full
}

function Read-Json([string]$path) {
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Assert-ApprovalPreflight([string]$root, [string]$receiptPath, [string]$contractPath,
                                   [string]$baseline, [string]$group, [string]$budget) {
    $receipt = Read-Json $receiptPath
    if ($receipt.state -ne "APPROVED" -or $receipt.approved -ne $true) {
        throw "APPROVAL_NOT_APPROVED: $receiptPath"
    }
    if ([int]$receipt.max_runs -ne 1 -or $receipt.automatic_retry -ne $false) {
        throw "APPROVAL_MISMATCH: max_runs/automatic_retry"
    }
    if ((Normalize $receipt.evidence_root) -ne (Normalize $root)) {
        throw "APPROVAL_MISMATCH: evidence_root"
    }
    if ($receipt.baseline -ne $baseline) { throw "APPROVAL_MISMATCH: baseline" }
    if ($receipt.scope -ne $group -or $receipt.pair_group -ne $group) {
        throw "APPROVAL_MISMATCH: scope/pair_group"
    }
    if ($receipt.budget_profile -ne $budget) { throw "APPROVAL_MISMATCH: budget_profile" }
    $contractSha = Get-Sha256File $contractPath
    if ($receipt.contract_sha256 -ne $contractSha) {
        throw "APPROVAL_MISMATCH: contract_sha256 does not match the contract file"
    }
}

function Add-Approval([string[]]$extra, [string]$root, [string]$baseline, [string]$ledgerSha) {
    if (-not $ApprovalFile) { return $extra }
    if (-not $ContractFile) {
        throw "APPROVAL_CONTRACT_REQUIRED: -ApprovalFile also requires -ContractFile"
    }
    Assert-ApprovalPreflight $root $ApprovalFile $ContractFile $baseline $ApprovalGroup $ApprovalBudget
    return $extra + @(
        "-Deval.approval.file=$(FullPath $ApprovalFile)",
        "-Deval.approval.required=true",
        "-Deval.approval.baseline=$baseline",
        "-Deval.approval.group=$ApprovalGroup",
        "-Deval.approval.budget=$ApprovalBudget",
        "-Deval.approval.contract.file=$(FullPath $ContractFile)",
        "-Deval.contract.required=true",
        "-Deval.frames.source=$(FullPath $FramesSource)",
        "-Deval.historical.ledger.sha256=$ledgerSha"
    )
}

function New-ContractFile([string]$ledgerPath, [string]$ledgerSha, [string]$materialPath,
                          [string]$framesPath, [string]$outPath, [string]$baseline) {
    if ((Get-Sha256File $ledgerPath) -ne $ledgerSha) {
        throw "LEDGER_SHA_MISMATCH: the supplied digest does not match $ledgerPath"
    }
    $config = Read-Json $materialPath
    $manifestPath = Join-Path $framesPath "frames-manifest.json"
    $manifest = Read-Json $manifestPath
    if ($manifest.frames.Count -ne 12) { throw "FRAME_MANIFEST_INVALID: expected 12 frames" }
    if (-not $manifest.source.video_sha256) { throw "FRAME_MANIFEST_INVALID: source video digest missing" }
    $bindings = [ordered]@{}
    foreach ($material in $config.materials) {
        $bindings[$material.key] = [ordered]@{
            video_sha256 = $material.binding.video_sha256
            comment_sha256 = $material.binding.comment_sha256
            danmaku_sha256 = $material.binding.danmaku_sha256
            dataset_version = if ($material.binding.dataset_version) { $material.binding.dataset_version } else { "baseline" }
        }
    }
    $prompt = "分析视频730013从2026-11-03到2026-11-10的评论和弹幕，结合视频画面，生成观众反馈HTML报告"
    $contract = [ordered]@{
        kind = "offline-evaluation-contract-v1"
        baseline = $baseline
        pair_group = $ApprovalGroup
        prompt_sha256 = Get-Sha256Text $prompt
        window_start = "2026-11-03 00:00:00"
        window_end = "2026-11-10 00:00:00"
        task_clock = "2026-11-16T00:00:00Z"
        budget_profile = $ApprovalBudget
        tool_config_version = "evaluation-entry-v1"
        sampling_strategy = "frame-12-window-full"
        material_config_sha256 = Get-Sha256File $materialPath
        material_bindings = $bindings
        frames_manifest_sha256 = Get-Sha256File $manifestPath
        frames_count = $manifest.frames.Count
        source_video_sha256 = $manifest.source.video_sha256
        ledger_sha256 = $ledgerSha
        roles = [ordered]@{
            controller_a = "deepseek-ai/DeepSeek-V4.1-Flash"
            controller_b = "deepseek-ai/DeepSeek-V4-Pro"
            analysis = $AnalysisModel
            vision = "MiniMax-M3"
        }
    }
    if (Test-Path -LiteralPath $outPath) { throw "CONTRACT_EXISTS: $outPath" }
    $parent = Split-Path -Parent $outPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $contract | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $outPath -Encoding UTF8
    return (Get-Sha256File $outPath)
}

$baseline = if ($ApprovalBaseline) { $ApprovalBaseline } else { (& git -C $repo rev-parse HEAD).Trim() }

if ($Mode -eq "Contract") {
    $ledgerPath = Assert-InputFile $Ledger "-Ledger"
    $materialPath = Assert-InputFile $MaterialConfig "-MaterialConfig"
    $framesPath = Assert-InputDir $FramesSource "-FramesSource"
    if (-not $LedgerSha256) { throw "Contract requires -LedgerSha256 (an independently trusted digest)" }
    if (-not $ContractOut) { throw "Contract requires -ContractOut" }
    $outPath = FullPath $ContractOut
    if (Test-Path -LiteralPath $outPath) { throw "CONTRACT_EXISTS: $outPath" }
    $sha = New-ContractFile $ledgerPath $LedgerSha256 $materialPath $framesPath $outPath $baseline
    "contract=$outPath"
    "sha256=$sha"
    exit 0
}

if ($Mode -eq "CanonPair") {
    if (-not $Ledger -or -not $MaterialConfig -or -not $FramesSource -or -not $ApprovalFile -or -not $ContractFile) {
        throw "CanonPair requires -Ledger -LedgerSha256 -MaterialConfig -FramesSource -ApprovalFile -ContractFile"
    }
    if (-not $LedgerSha256) { throw "CanonPair requires -LedgerSha256 (an independently trusted digest)" }
}
if ($Mode -in @("SyntheticPair", "AStopPair")) {
    if (-not $Ledger) { $Ledger = Join-Path $agent "eval/fixtures/synthetic/batch-cumulative.json" }
    if (-not $MaterialConfig) { $MaterialConfig = Join-Path $agent "eval/report-trial-synthetic.example.json" }
    if (-not $FramesSource) { $FramesSource = Join-Path $agent "eval/fixtures/synthetic/frames" }
    if (-not $LedgerSha256) { $LedgerSha256 = Get-Sha256File (FullPath $Ledger) }
}

$needsPairInputs = $Mode -in @("SyntheticPair", "AStopPair", "CanonPair")
if ($needsPairInputs) {
    $Ledger = Assert-InputFile $Ledger "-Ledger"
    $MaterialConfig = Assert-InputFile $MaterialConfig "-MaterialConfig"
    $FramesSource = Assert-InputDir $FramesSource "-FramesSource"
    if ($LedgerSha256 -ne (Get-Sha256File $Ledger)) {
        throw "LEDGER_SHA_MISMATCH: the supplied digest does not match $Ledger"
    }
    if (-not $EvidenceRoot) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $EvidenceRoot = Join-Path $repo "target/evaluation/offline-entry/$Mode-$stamp"
    }
    $EvidenceRoot = Assert-EvidenceRoot $EvidenceRoot
    Assert-NotOverlap $EvidenceRoot $Ledger "-Ledger"
    Assert-NotOverlap $EvidenceRoot $MaterialConfig "-MaterialConfig"
    Assert-NotOverlap $EvidenceRoot $FramesSource "-FramesSource"
    if ($ContractFile) { $ContractFile = Assert-InputFile $ContractFile "-ContractFile"; Assert-NotOverlap $EvidenceRoot $ContractFile "-ContractFile" }
    if ($ApprovalFile) {
        $ApprovalFile = Assert-InputFile $ApprovalFile "-ApprovalFile"
        Assert-NotOverlap $EvidenceRoot $ApprovalFile "-ApprovalFile"
        if (-not $ContractFile) { throw "APPROVAL_CONTRACT_REQUIRED: -ApprovalFile also requires -ContractFile" }
        Assert-ApprovalPreflight $EvidenceRoot $ApprovalFile $ContractFile $baseline $ApprovalGroup $ApprovalBudget
    }
} else {
    if (-not $EvidenceRoot) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $EvidenceRoot = Join-Path $repo "target/evaluation/offline-entry/$Mode-$stamp"
    }
    $EvidenceRoot = Assert-EvidenceRoot $EvidenceRoot
}

New-Item -ItemType Directory -Path $EvidenceRoot | Out-Null

function Invoke-Test([string]$Tests, [string[]]$Extra) {
    $arguments = @("-B", "-ntp", "-f", (Join-Path $agent "pom.xml"), "test", "-Dtest=$Tests") + $Extra
    if ($Offline) { $arguments = @("-o") + $arguments }
    Push-Location $repo
    try {
        $output = & $Mvn @arguments 2>&1
        $code = $LASTEXITCODE
        $output | Set-Content -LiteralPath (Join-Path $EvidenceRoot ("mvn-" + ($Tests -replace '[,]', '-') + ".log")) -Encoding UTF8
        return $code
    } finally { Pop-Location }
}

$summary = [ordered]@{ mode = $Mode; evidence_root = $EvidenceRoot; started_at = (Get-Date).ToString("o") }
$exitA = $null; $exitB = $null
$commonPair = @(
    "-Deval.canon.enabled=true",
    "-Deval.evidence.root=$EvidenceRoot",
    "-Deval.analysis.model=$AnalysisModel",
    "-Deval.approval.baseline=$baseline",
    "-Deval.approval.group=$ApprovalGroup",
    "-Deval.approval.budget=$ApprovalBudget",
    "-Deval.historical.ledger.sha256=$LedgerSha256",
    "-Deval.material.config=$MaterialConfig",
    "-Deval.frames.source=$FramesSource"
)

switch ($Mode) {
    "SyntheticPair" {
        $extra = $commonPair + @(
            "-Deval.historical.batch.ledger=$Ledger",
            "-Deval.opening.rows=2", "-Deval.expected.comments=4",
            "-Deval.expected.danmaku=6", "-Deval.expected.total=10")
        $extra = Add-Approval $extra $EvidenceRoot $baseline $LedgerSha256
        $exitA = Invoke-Test "EvaluationVideoCanonAProcessTest" $extra
        if ($exitA -ne 0) {
            $stopProofExtra = $extra + @("-Deval.canon.stop-proof=true")
            $summary.a_stop_proof_exit = Invoke-Test "EvaluationVideoCanonStopProofTest" $stopProofExtra
        }
        if ($exitA -eq 0 -or ($null -ne $summary.a_stop_proof_exit -and $summary.a_stop_proof_exit -eq 0)) {
            $exitB = Invoke-Test "EvaluationVideoCanonBProcessTest" $extra
        }
    }
    "AStopPair" {
        $extra = $commonPair + @(
            "-Deval.historical.batch.ledger=$Ledger",
            "-Deval.opening.rows=2", "-Deval.expected.comments=4",
            "-Deval.expected.danmaku=6", "-Deval.expected.total=10")
        $extra = Add-Approval $extra $EvidenceRoot $baseline $LedgerSha256
        $aExtra = $extra + @("-Deval.canon.a-stop=true", "-Deval.a.cancel.after.launch=true")
        $exitA = Invoke-Test "EvaluationVideoCanonAStopProcessTest" $aExtra
        if ($exitA -eq 0) { throw "A_STOP_NOT_TRIGGERED: the A failure probe unexpectedly succeeded" }
        $summary.a_stop_expected_exit = $exitA
        $stopProofExtra = $extra + @("-Deval.canon.stop-proof=true")
        $summary.a_stop_proof_exit = Invoke-Test "EvaluationVideoCanonStopProofTest" $stopProofExtra
        if ($summary.a_stop_proof_exit -eq 0) {
            $exitB = Invoke-Test "EvaluationVideoCanonBProcessTest" $extra
        }
    }
    "CanonPair" {
        $extra = $commonPair + @(
            "-Deval.historical.batch.ledger=$Ledger",
            "-Deval.opening.rows=24", "-Deval.expected.comments=58",
            "-Deval.expected.danmaku=137", "-Deval.expected.total=195")
        $extra = Add-Approval $extra $EvidenceRoot $baseline $LedgerSha256
        $exitA = Invoke-Test "EvaluationVideoCanonAProcessTest" $extra
        if ($exitA -ne 0) {
            $stopProofExtra = $extra + @("-Deval.canon.stop-proof=true")
            $summary.a_stop_proof_exit = Invoke-Test "EvaluationVideoCanonStopProofTest" $stopProofExtra
        }
        if ($exitA -eq 0 -or ($null -ne $summary.a_stop_proof_exit -and $summary.a_stop_proof_exit -eq 0)) {
            $exitB = Invoke-Test "EvaluationVideoCanonBProcessTest" $extra
        }
    }
    "ReportArguments" {
        $exitA = Invoke-Test "EvaluationReportArgumentsTest" @("-Deval.evidence.root=$EvidenceRoot")
    }
    "Ledger" { $exitA = Invoke-Test "EvaluationSyntheticLedgerTest" @() }
    "Fee" { $exitA = Invoke-Test "EvaluationFeeGateTest,EvaluationPaidExitGuardTest,EvaluationSyntheticLedgerTest" @() }
    "Approval" { $exitA = Invoke-Test "EvaluationApprovalTest,EvaluationApprovalEntryTest" @() }
    "Stop" {
        $stopLedger = Join-Path $agent "eval/fixtures/synthetic/stop-ledger.json"
        $exitA = Invoke-Test "EvaluationAOnlyStopTest" @(
            "-Deval.canon.enabled=true", "-Deval.evidence.root=$EvidenceRoot",
            "-Deval.historical.batch.ledger=$stopLedger")
    }
    "CanonStop" {
        $exitA = Invoke-Test "EvaluationVideoCanonStopTest" @(
            "-Deval.canon.enabled=true", "-Deval.evidence.root=$EvidenceRoot")
    }
}
$summary.exit_a = $exitA
$summary.exit_b = $exitB
$summary.finished_at = (Get-Date).ToString("o")
$reportA = Join-Path $EvidenceRoot "a-report.json"
$reportB = Join-Path $EvidenceRoot "b-report.json"
if (Test-Path $reportA) {
    $r = Get-Content -Raw $reportA | ConvertFrom-Json
    $summary.a_report = [ordered]@{ status = $r.status; pid = $r.pid; sha256 = $r.report_sha256; bytes = $r.report_bytes }
}
if (Test-Path $reportB) {
    $r = Get-Content -Raw $reportB | ConvertFrom-Json
    $summary.b_report = [ordered]@{ status = $r.status; pid = $r.pid; sha256 = $r.report_sha256; bytes = $r.report_bytes }
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceRoot "run-summary.json") -Encoding UTF8
$ok = if ($null -ne $exitB) {
    ($exitB -eq 0) -and ($exitA -eq 0 -or ($null -ne $summary.a_stop_proof_exit -and $summary.a_stop_proof_exit -eq 0))
} else {
    ($exitA -eq 0)
}
if (-not $ok) { exit 1 }
exit 0
