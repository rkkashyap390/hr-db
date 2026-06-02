pipeline {
  agent any

  options {
    timestamps()
    disableConcurrentBuilds()
  }

  parameters {
    choice(name: 'PACKAGE_NAME', choices: ['PKG_LIQUIBASE_DEMO'], description: 'Package name to sync from DEV2.')
  }
  environment {
    GIT_REPO_URL      = 'https://github.com/rkkashyap390/hr-db.git'
    GIT_BRANCH        = 'main'
    GIT_CRED_ID       = 'github-hr-db-pat'
    SQLCL_EXE         = 'C:\\tools\\sqlcl\\bin\\sql.exe'
    SQLPLUS_EXE       = 'C:\\app\\dbhomeFree\\bin\\sqlplus.exe'
    TNS_ADMIN_DIR     = 'C:\\app\\dbhomeFree\\network\\admin'
    DEV2_CONNECT      = '//127.0.0.1:1521/freepdb1'
    DEV2_USER         = 'HR'
    DEV2_PASSWORD     = '12345678'
    SIT2_CONNECT      = '//127.0.0.1:1521/freepdb1'
    SIT2_USER         = 'HR_SIT'
    SIT2_PASSWORD     = '12345678'
    PKG_CHANGELOG_XML = 'db/hr/changelogs/001-packages.xml'
    PKG_CHANGELOG_DIR = 'db/hr/changelogs/pkg'
    PKG_SQL_DIR       = 'db/hr/plsql'
    JIRA_KEY          = 'JIRA-0000'
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

    stage('Validate Package Parameter') {
      steps {
        script {
          def provided = params.PACKAGE_NAME?.trim()?.toUpperCase()
          if (!provided) {
            error('PACKAGE_NAME is required. Enter the package name manually for now.')
          }

          env.SELECTED_PACKAGE = provided
          echo "Selected package: ${env.SELECTED_PACKAGE}"
        }
      }
    }

    stage('Pull Package From DEV2') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          $env:TNS_ADMIN = "${env:TNS_ADMIN_DIR}".Trim()

          $pkg = "${env:SELECTED_PACKAGE}".Trim().ToUpper()
          if ([string]::IsNullOrWhiteSpace($pkg)) { throw "Selected package is empty." }

          $sqlDir = Join-Path "${env:WORKSPACE}" "${env:PKG_SQL_DIR}"
          New-Item -ItemType Directory -Force -Path $sqlDir | Out-Null
          New-Item -ItemType Directory -Force -Path (Join-Path "${env:WORKSPACE}" "${env:PKG_CHANGELOG_DIR}") | Out-Null

          $specFile = Join-Path $sqlDir ("{0}.sql" -f $pkg.ToLower())
          $bodyFile = Join-Path $sqlDir ("{0}.plb" -f $pkg.ToLower())

          $prepSql = @"
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
          $prepFile = Join-Path "${env:WORKSPACE}" "pkg-prep-dev2-pull.sql"
          Set-Content -LiteralPath $prepFile -Value $prepSql -Encoding Ascii
          & "${env:SQLPLUS_EXE}" -L /nolog "@$prepFile"
          if ($LASTEXITCODE -ne 0) { throw "SYSDBA pre-step failed before DEV2 package pull" }

          $pullSql = @"
whenever oserror exit failure
whenever sqlerror exit failure
set long 2000000
set longchunksize 32767
set pagesize 0
set linesize 32767
set trimspool on
set feedback off
set heading off
set verify off
set echo off
set serveroutput on size unlimited format wrapped
connect ${env:DEV2_USER}/${env:DEV2_PASSWORD}@${env:DEV2_CONNECT}
set define off
declare
  l_spec_count number;
  l_body_count number;
begin
  select count(*) into l_spec_count from user_source where name = upper('$pkg') and type = 'PACKAGE';
  select count(*) into l_body_count from user_source where name = upper('$pkg') and type = 'PACKAGE BODY';
  if l_spec_count = 0 then
    raise_application_error(-20001, 'Package spec not found in DEV2: $pkg');
  end if;
  if l_body_count = 0 then
    raise_application_error(-20002, 'Package body not found in DEV2: $pkg');
  end if;
end;
/
spool "$specFile"
prompt create or replace
begin
  for src in (
    select text
    from user_source
    where name = upper('$pkg')
      and type = 'PACKAGE'
    order by line
  ) loop
    sys.dbms_output.put_line(rtrim(src.text, chr(10) || chr(13)));
  end loop;
end;
/
prompt /
spool off
spool "$bodyFile"
prompt create or replace
begin
  for src in (
    select text
    from user_source
    where name = upper('$pkg')
      and type = 'PACKAGE BODY'
    order by line
  ) loop
    sys.dbms_output.put_line(rtrim(src.text, chr(10) || chr(13)));
  end loop;
end;
/
prompt /
spool off
exit success
"@
          $pullFile = Join-Path "${env:WORKSPACE}" "pkg-pull-dev2.sql"
          Set-Content -LiteralPath $pullFile -Value $pullSql -Encoding Ascii
          & "${env:SQLCL_EXE}" -S -thin -nohistory /nolog "@$pullFile"
          if ($LASTEXITCODE -ne 0) { throw "Failed pulling package DDL from DEV2: $pkg" }

          if (-not (Test-Path -LiteralPath $specFile)) { throw "Spec file missing: $specFile" }
          if (-not (Test-Path -LiteralPath $bodyFile)) { throw "Body file missing: $bodyFile" }

          $specSize = (Get-Item -LiteralPath $specFile).Length
          $bodySize = (Get-Item -LiteralPath $bodyFile).Length
          if ($specSize -le 0 -or $bodySize -le 0) { throw "Extracted package file is empty for $pkg" }

          Write-Host "Pulled package from DEV2: $pkg"
          Write-Host "Spec: $specFile"
          Write-Host "Body: $bodyFile"
        '''
      }
    }

    stage('Create/Update Package Changelog') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $pkg = "${env:SELECTED_PACKAGE}".Trim().ToUpper()
          $changelogDir = Join-Path "${env:WORKSPACE}" "${env:PKG_CHANGELOG_DIR}"
          $pkgSqlDirRel = "${env:PKG_SQL_DIR}".Replace("\\","/")
          $pkgFileBase = $pkg.ToLower()
          $changelogFile = Join-Path $changelogDir ("auto-{0}.xml" -f $pkgFileBase)
          $sitCheckFile = Join-Path "${env:WORKSPACE}" "pkg-exists-sit2.out"

          if (-not (Test-Path -LiteralPath $changelogDir)) {
            New-Item -ItemType Directory -Force -Path $changelogDir | Out-Null
          }

          $prepSql = @"
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
          $prepFile = Join-Path "${env:WORKSPACE}" "pkg-prep-sit2-check.sql"
          Set-Content -LiteralPath $prepFile -Value $prepSql -Encoding Ascii
          & "${env:SQLPLUS_EXE}" -L /nolog "@$prepFile"
          if ($LASTEXITCODE -ne 0) { throw "SYSDBA pre-step failed before SIT2 package existence check" }

          $sitCheckSql = @"
whenever oserror exit failure
whenever sqlerror exit failure
set heading off
set feedback off
set pagesize 0
set verify off
set echo off
set trimspool on
connect ${env:SIT2_USER}/${env:SIT2_PASSWORD}@${env:SIT2_CONNECT}
spool "$sitCheckFile"
select count(*) from user_objects where object_type = 'PACKAGE' and object_name = upper('$pkg');
spool off
exit success
"@
          $sitCheckScript = Join-Path "${env:WORKSPACE}" "pkg-check-sit2.sql"
          Set-Content -LiteralPath $sitCheckScript -Value $sitCheckSql -Encoding Ascii
          & "${env:SQLCL_EXE}" -S -thin -nohistory /nolog "@$sitCheckScript"
          if ($LASTEXITCODE -ne 0) { throw "Failed checking package existence on SIT2: $pkg" }

          $sitCountLine = Get-Content -LiteralPath $sitCheckFile | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[0-9]+$' } | Select-Object -First 1
          $existsOnSit2 = ($sitCountLine -and ([int]$sitCountLine -gt 0))

          $existingChangelog = Get-ChildItem -LiteralPath $changelogDir -Filter '*.xml' -File |
            Where-Object {
              $xml = Get-Content -LiteralPath $_.FullName -Raw
              $xml -like "*$pkgFileBase.sql*" -or
              $xml -like "*$pkgFileBase.plb*" -or
              $xml -like "*pkg-$pkgFileBase*"
            } |
            Select-Object -First 1

          if ($existingChangelog) {
            $changelogFile = $existingChangelog.FullName
            Write-Host "Package changelog already exists; preserving existing runOnChange changelog: $changelogFile"
          } elseif ($existsOnSit2) {
            Write-Host "Package already exists on SIT2; skipping new changeset creation for: $pkg"
          } else {
            $changeSetId = "pkg-${pkgFileBase}"
            $changeLogContent = @"
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="$changeSetId" author="jenkins-bot" runOnChange="true">
        <sqlFile path="$pkgSqlDirRel/$pkgFileBase.sql" relativeToChangelogFile="false" endDelimiter="/" splitStatements="false"/>
        <sqlFile path="$pkgSqlDirRel/$pkgFileBase.plb" relativeToChangelogFile="false" endDelimiter="/" splitStatements="false"/>
    </changeSet>

</databaseChangeLog>
"@
            Set-Content -LiteralPath $changelogFile -Value $changeLogContent -Encoding UTF8
            Write-Host "Created package changelog: $changelogFile"
          }

          $masterPkgXml = Join-Path "${env:WORKSPACE}" "${env:PKG_CHANGELOG_XML}"
          if (-not (Test-Path -LiteralPath $masterPkgXml)) {
            throw "Packages changelog file not found: $masterPkgXml"
          }

          if (Test-Path -LiteralPath $changelogFile) {
            $relInclude = "./pkg/$([System.IO.Path]::GetFileName($changelogFile))"
            $includeLine = "  <include file=`"$relInclude`" relativeToChangelogFile=`"true`"/>"
            $masterContent = Get-Content -LiteralPath $masterPkgXml -Raw
            if ($masterContent -notlike "*$relInclude*") {
              $masterContent = $masterContent -replace "</databaseChangeLog>", "$includeLine`r`n</databaseChangeLog>"
              Set-Content -LiteralPath $masterPkgXml -Value $masterContent -Encoding UTF8
              Write-Host "Added include in 001-packages.xml for: $relInclude"
            } else {
              Write-Host "Include already exists: $relInclude"
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
            $pkg = "${env:SELECTED_PACKAGE}".Trim().ToLower()
            $branch = "auto/pkg-sync-$pkg-$jira-${env:BUILD_NUMBER}"

            git config user.name "jenkins-bot"
            git config user.email "jenkins-bot@local"

            git fetch origin ${env:GIT_BRANCH}
            git checkout -B ${env:GIT_BRANCH} origin/${env:GIT_BRANCH}
            git checkout -b $branch

            git add "${env:PKG_SQL_DIR}" "${env:PKG_CHANGELOG_DIR}" "${env:PKG_CHANGELOG_XML}"
            $changes = git status --porcelain
            if (-not $changes) {
              Write-Host "No package sync changes to commit."
              exit 0
            }

            git commit -m "Auto package sync ${env:SELECTED_PACKAGE} from DEV2 (${env:JIRA_KEY}) [build ${env:BUILD_NUMBER}]"

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



