Unicode True
RequestExecutionLevel admin
SetCompressor /SOLID lzma
SetCompressorDictSize 64

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"

!ifndef APP_VERSION
  !define APP_VERSION "0.1.4"
!endif
!ifndef PUBLISH_DIR
  !error "PUBLISH_DIR is required."
!endif
!ifndef APP_ICON
  !error "APP_ICON is required."
!endif
!ifndef WELCOME_BITMAP
  !error "WELCOME_BITMAP is required."
!endif
!ifndef HEADER_BITMAP
  !error "HEADER_BITMAP is required."
!endif
!ifndef OUTPUT_FILE
  !define OUTPUT_FILE "Durumari-Remote-Setup-${APP_VERSION}-x64.exe"
!endif

!define APP_NAME "두루마리 Windows 리모콘"
!define APP_PUBLISHER "Durumari"
!define APP_EXE "Durumari.Remote.exe"
!define APP_REG_KEY "Software\Durumari\Remote"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Durumari.Remote"

Name "${APP_NAME}"
OutFile "${OUTPUT_FILE}"
InstallDir "$PROGRAMFILES64\Durumari Remote"
InstallDirRegKey HKLM "${UNINSTALL_KEY}" "InstallLocation"
BrandingText "${APP_NAME}"
Icon "${APP_ICON}"
UninstallIcon "${APP_ICON}"
WindowIcon on
ShowInstDetails show
ShowUninstDetails show

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey /LANG=1042 "ProductName" "${APP_NAME}"
VIAddVersionKey /LANG=1042 "CompanyName" "${APP_PUBLISHER}"
VIAddVersionKey /LANG=1042 "FileDescription" "${APP_NAME} 설치 프로그램"
VIAddVersionKey /LANG=1042 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1042 "ProductVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1042 "LegalCopyright" "Copyright © ${APP_PUBLISHER}"

!define MUI_ABORTWARNING
!define MUI_ICON "${APP_ICON}"
!define MUI_UNICON "${APP_ICON}"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${WELCOME_BITMAP}"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_RIGHT
!define MUI_HEADERIMAGE_BITMAP "${HEADER_BITMAP}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "두루마리 Windows 리모콘 실행"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Korean"

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "이 설치 프로그램은 64비트 Windows에서만 실행할 수 있습니다."
    Abort
  ${EndIf}
  SetRegView 64
  SetShellVarContext all
FunctionEnd

Function un.onInit
  SetRegView 64
  SetShellVarContext all
FunctionEnd

Section "두루마리 Windows 리모콘" SecMain
  SectionIn RO
  SetRegView 64
  SetShellVarContext all

  ; 업그레이드 중 실행 파일이 잠기지 않도록 기존 리모콘을 종료합니다.
  nsExec::ExecToLog 'taskkill /IM "${APP_EXE}" /T /F'

  SetOutPath "$INSTDIR"
  File "/oname=${APP_EXE}" "${PUBLISH_DIR}\${APP_EXE}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\두루마리"
  CreateShortcut "$SMPROGRAMS\두루마리\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0

  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\${APP_EXE}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "Publisher" "${APP_PUBLISHER}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "${UNINSTALL_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetRegView 64
  SetShellVarContext all

  nsExec::ExecToLog 'taskkill /IM "${APP_EXE}" /T /F'

  Delete "$SMPROGRAMS\두루마리\${APP_NAME}.lnk"
  RMDir "$SMPROGRAMS\두루마리"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKLM "${UNINSTALL_KEY}"
  DeleteRegKey HKCU "${APP_REG_KEY}"
SectionEnd
