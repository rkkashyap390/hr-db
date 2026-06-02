pipeline {
  agent any

  triggers {
    githubPush()
  }

  options {
    timestamps()
    disableConcurrentBuilds()
  }

  environment {
    GIT_REPO_URL   = 'https://github.com/rkkashyap390/hr-db.git'
    GIT_BRANCH     = 'main'
    GIT_CRED_ID    = 'github-hr-db-pat'
    SQLCL_EXE      = 'C:\\tools\\sqlcl\\bin\\sql.exe'
    SQLPLUS_EXE    = 'C:\\app\\dbhomeFree\\bin\\sqlplus.exe'
    TNS_ADMIN_DIR  = 'C:\\app\\dbhomeFree\\network\\admin'
    CHANGELOG_ROOT = 'db/hr/changelog-root.xml'
    AUTO_DIR       = 'db/hr/changelogs/auto'
    HR_URL         = 'jdbc:oracle:thin:@//127.0.0.1:1521/freepdb1'
    HR_USER        = 'HR'
    HR_PASSWORD    = '12345678'
    HRSIT_URL      = 'jdbc:oracle:thin:@//127.0.0.1:1521/freepdb1'
    HRSIT_USER     = 'HR_SIT'
    HRSIT_PASSWORD = '12345678'
    EXCLUDE_OBJECTS = 'table:DATABASECHANGELOG,table:DATABASECHANGELOGLOCK,table:DATABASECHANGELOG_ACTIONS,view:DATABASECHANGELOG_DETAILS'
    JIRA_KEY       = 'JIRA-0000'
  }

  stages {
    stage('Prepare Workspace') {
      steps {
        deleteDir()
      }
    }

    stage('Checkout Main') {
      steps {
        checkout([
          $class: 'GitSCM',
          branches: [[name: "*/${env.GIT_BRANCH}"]],
          userRemoteConfigs: [[url: env.GIT_REPO_URL, credentialsId: env.GIT_CRED_ID]],
          extensions: [[$class: 'CleanBeforeCheckout']]
        ])
      }
    }

    stage('Generate Diff Changelog (HR vs HR_SIT)') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          $env:TNS_ADMIN = "${env:TNS_ADMIN_DIR}".Trim()

          if (-not (Test-Path -LiteralPath "${env:SQLCL_EXE}")) {
            throw "SQLcl not found: ${env:SQLCL_EXE}"
          }
          if (-not (Test-Path -LiteralPath "${env:SQLPLUS_EXE}")) {
            throw "SQLPlus not found: ${env:SQLPLUS_EXE}"
          }
          if (-not (Test-Path -LiteralPath "${env:TNS_ADMIN_DIR}")) {
            throw "TNS_ADMIN folder not found: ${env:TNS_ADMIN_DIR}"
          }

          New-Item -ItemType Directory -Force -Path "${env:WORKSPACE}\\${env:AUTO_DIR}" | Out-Null

          $ts = Get-Date -Format "yyyyMMdd-HHmmss"
          $jira = "${env:JIRA_KEY}".Trim().Replace(" ", "_")
          if ([string]::IsNullOrWhiteSpace($jira)) { $jira = "JIRA-0000" }

          $schemaFileRel = "${env:AUTO_DIR}/${ts}-${jira}-ddl.xml"
          $schemaFileAbs = Join-Path "${env:WORKSPACE}" $schemaFileRel
          $dataFileRel = "${env:AUTO_DIR}/${ts}-${jira}-data.xml"
          $dataFileAbs = Join-Path "${env:WORKSPACE}" $dataFileRel

          $sysSql = @"
whenever oserror exit failure
whenever sqlerror continue
connect / as sysdba
begin
  execute immediate 'alter pluggable database FREEPDB1 open';
exception
  when others then
    if sqlcode != -65019 then
      raise;
    end if;
end;
/
alter system register;
alter session set container=FREEPDB1;
alter system register;
whenever sqlerror exit failure
exit success
"@
          $sysScript = Join-Path "${env:WORKSPACE}" "prepare-pdb-diff.sql"
          Set-Content -LiteralPath $sysScript -Value $sysSql -Encoding Ascii

          $sysOut = Join-Path "${env:WORKSPACE}" "prepare-pdb-diff.out"
          $sysErr = Join-Path "${env:WORKSPACE}" "prepare-pdb-diff.err"
          if (Test-Path $sysOut) { Remove-Item -LiteralPath $sysOut -Force }
          if (Test-Path $sysErr) { Remove-Item -LiteralPath $sysErr -Force }

          Write-Host "Preparing FREEPDB1 via SQLPlus SYSDBA..."
          $sysProc = Start-Process -FilePath "${env:SQLPLUS_EXE}" -ArgumentList @('-L','/nolog',"@$sysScript") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $sysOut -RedirectStandardError $sysErr
          if (Test-Path $sysOut) { Get-Content -LiteralPath $sysOut | ForEach-Object { Write-Host $_ } }
          if (Test-Path $sysErr) { Get-Content -LiteralPath $sysErr | ForEach-Object { Write-Host $_ } }
          if ($sysProc.ExitCode -ne 0) { throw "SQLPlus SYSDBA pre-step failed for diff-changelog" }

          $sqlScript = @"
whenever oserror exit failure
whenever sqlerror exit failure
connect ${env:HRSIT_USER}/${env:HRSIT_PASSWORD}@//127.0.0.1:1521/freepdb1
lb diff-changelog -changelog-file "$schemaFileAbs" -reference-url "${env:HR_URL}" -reference-username "${env:HR_USER}" -reference-password "${env:HR_PASSWORD}" -exclude-objects "${env:EXCLUDE_OBJECTS}"
connect ${env:HR_USER}/${env:HR_PASSWORD}@//127.0.0.1:1521/freepdb1
lb data -output-file "$dataFileAbs"
exit success
"@

          $scriptPath = Join-Path "${env:WORKSPACE}" "capture-dev2.sql"
          Set-Content -LiteralPath $scriptPath -Value $sqlScript -Encoding Ascii

          $outFile = Join-Path "${env:WORKSPACE}" "capture-dev2.out"
          $errFile = Join-Path "${env:WORKSPACE}" "capture-dev2.err"
          if (Test-Path $outFile) { Remove-Item -LiteralPath $outFile -Force }
          if (Test-Path $errFile) { Remove-Item -LiteralPath $errFile -Force }

          $proc = Start-Process -FilePath "${env:SQLCL_EXE}" -ArgumentList @('-S','-thin','-nohistory','/nolog',"@$scriptPath") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
          $exitCode = $proc.ExitCode

          $noisePatterns = @(
            'org.jline.utils.Log logr',
            'Failed to save history',
            'UserPrincipalNotFoundException',
            'oracle.dbtools.raptor.console.impl.SqlclHistory',
            'at oracle.dbtools.',
            'at java.base/sun.nio.fs.Windows'
          )

          $lines = @()
          if (Test-Path $outFile) { $lines += Get-Content -LiteralPath $outFile }
          if (Test-Path $errFile) { $lines += Get-Content -LiteralPath $errFile }
          foreach ($line in $lines) {
            $txt = $line.ToString()
            $isNoise = $false
            foreach ($p in $noisePatterns) {
              if ($txt -like "*$p*") { $isNoise = $true; break }
            }
            if (-not $isNoise) { Write-Host $txt }
          }

          if ($exitCode -ne 0) { throw "lb diff-changelog failed" }

          $generatedAny = $false
          if (Test-Path -LiteralPath $schemaFileAbs) {
            Add-Content -LiteralPath "${env:WORKSPACE}\\capture-files.txt" -Value $schemaFileRel
            Write-Host "Generated: $schemaFileRel"
            $generatedAny = $true
          } else {
            Write-Host "No DDL diff changelog generated."
          }

          if (Test-Path -LiteralPath $dataFileAbs) {
            Add-Content -LiteralPath "${env:WORKSPACE}\\capture-files.txt" -Value $dataFileRel
            Write-Host "Generated: $dataFileRel"
            $generatedAny = $true
          } else {
            Write-Host "No data changelog generated."
          }

          if (-not $generatedAny) {
            Write-Host "No changelog files generated in this run."
            if (-not (Test-Path -LiteralPath "${env:WORKSPACE}\\capture-files.txt")) {
              New-Item -ItemType File -Path "${env:WORKSPACE}\\capture-files.txt" -Force | Out-Null
            }
          }
        '''
      }
    }

    stage('Register Includes') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $root = Join-Path "${env:WORKSPACE}" "${env:CHANGELOG_ROOT}"
          if (-not (Test-Path -LiteralPath $root)) { throw "Root changelog not found: $root" }

          $files = Get-Content -LiteralPath "${env:WORKSPACE}\\capture-files.txt" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

          foreach ($rel in $files) {
            $includeLine = "  <include file=`"$rel`" relativeToChangelogFile=`"true`"/>"
            $content = Get-Content -LiteralPath $root -Raw
            if ($content -notlike "*$rel*") {
              $content = $content -replace "</databaseChangeLog>", "$includeLine`r`n</databaseChangeLog>"
              Set-Content -LiteralPath $root -Value $content -Encoding UTF8
              Write-Host "Added include for: $rel"
            } else {
              Write-Host "Include already exists for: $rel"
            }
          }
        '''
      }
    }

    stage('Create Branch And Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: env.GIT_CRED_ID, usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
          powershell '''
            $ErrorActionPreference = "Stop"

            $jira = "${env:JIRA_KEY}".Trim().Replace(" ", "_")
            if ([string]::IsNullOrWhiteSpace($jira)) { $jira = "JIRA-0000" }
            $branch = "auto/changelog-$jira-${env:BUILD_NUMBER}"

            git config user.name "jenkins-bot"
            git config user.email "jenkins-bot@local"

            git fetch origin ${env:GIT_BRANCH}
            git checkout -B ${env:GIT_BRANCH} origin/${env:GIT_BRANCH}
            git checkout -b $branch

            git add "${env:CHANGELOG_ROOT}" "${env:AUTO_DIR}"
            $changes = git status --porcelain
            if (-not $changes) {
              Write-Host "No changes to commit."
              exit 0
            }

            git commit -m "Auto diff-changelog HR vs HR_SIT (${env:JIRA_KEY}) [build ${env:BUILD_NUMBER}]"

            $repoNoProto = "${env:GIT_REPO_URL}" -replace "^https://", ""
            $pushUrl = "https://$env:GIT_USER:$env:GIT_TOKEN@$repoNoProto"
            git push $pushUrl $branch
            if ($LASTEXITCODE -ne 0) { throw "git push failed for branch $branch" }

            Write-Host "Pushed branch: $branch"
          '''
        }
      }
    }
  }
}
