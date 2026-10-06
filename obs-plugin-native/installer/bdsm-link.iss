; Instalador do BDSM Link (plugin nativo do OBS Studio) - Inno Setup 6.
;
; Layout recomendado pela documentacao do OBS (obsproject.com/kb/plugins-guide):
;   %PROGRAMDATA%\obs-studio\plugins\<nome>\bin\64bit\<nome>.dll   (o nome da DLL = nome da pasta)
;   %PROGRAMDATA%\obs-studio\plugins\<nome>\data\locale\*.ini
; O local legado (Program Files\obs-studio\obs-plugins\64bit) esta obsoleto e NAO e usado aqui.
; Pastas de plugins que o OBS 28+ (inclusive o 32.x) varre:
;   %PROGRAMDATA%\obs-studio\plugins    PADRAO (administrador) - recomendado pela documentacao
;   %APPDATA%\obs-studio\plugins        alternativa so para o usuario: no assistente escolha
;                                       "Instalar somente para mim" (Install for me only); nao exige administrador
; ({autoappdata} vira uma ou outra conforme o modo de instalacao escolhido.)
;
; A pagina "Pre-requisitos" (secao [Code]) so AVISA (nunca bloqueia): OBS Studio, DistroAV e NDI Runtime.
; O instalador NAO instala nem embute o DistroAV nem o NDI (licencas): so aponta os links oficiais.
;
; Compilar (o CI faz isto):
;   ISCC.exe /DAppVersion=1.0.0 /DSourceDir=..\release\RelWithDebInfo\bdsm-link installer\bdsm-link.iss
;
; ATENCAO: nunca compilado/testado fora do CI (o PC de desenvolvimento nao tem o Inno Setup).

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\release\RelWithDebInfo\bdsm-link"
#endif

[Setup]
AppId={{9ED79503-86F2-41B1-974B-5C8C3FE3880B}
AppName=BDSM Link para OBS Studio
AppVersion={#AppVersion}
AppVerName=BDSM Link {#AppVersion}
AppPublisher=Braga Digital Studio
; {autoappdata} = %PROGRAMDATA% (admin, padrao) ou %APPDATA% (somente usuario)
DefaultDirName={autoappdata}\obs-studio\plugins\bdsm-link
; A pasta TEM de ser ...\plugins\bdsm-link (o OBS procura bin\64bit\<pasta>.dll): sem pagina de pasta.
DisableDirPage=yes
UsePreviousAppDir=yes
DisableProgramGroupPage=yes
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog commandline
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\release
OutputBaseFilename=bdsm-link-{#AppVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName=BDSM Link para OBS Studio
CloseApplications=no
LicenseFile=..\LICENSE

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; Layout identico ao do ZIP do CI: bdsm-link\bin\64bit\bdsm-link.dll e bdsm-link\data\locale\*.ini
Source: "{#SourceDir}\bin\64bit\*"; DestDir: "{app}\bin\64bit"; Excludes: "*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\data\*"; DestDir: "{app}\data"; Flags: ignoreversion recursesubdirs createallsubdirs

[UninstallDelete]
; O desinstalador (registrado em Configuracoes > Aplicativos) remove os arquivos instalados e a pasta se
; ficar vazia. O token pareado (%APPDATA%\obs-studio\plugin_config\bdsm-link) NAO e apagado.
Type: dirifempty; Name: "{app}"

[Messages]
brazilianportuguese.WelcomeLabel2=Este assistente instalará o [name/ver] no OBS Studio 32.x.%n%nANTES DE CONTINUAR: FECHE o OBS Studio (a DLL do plugin não pode ser substituída com o OBS aberto).%n%nO instalador não inclui o DistroAV nem o NDI: eles são necessários para receber o vídeo do celular e serão verificados no próximo passo (sem bloquear a instalação).
english.WelcomeLabel2=This wizard will install [name/ver] for OBS Studio 32.x.%n%nBEFORE YOU CONTINUE: CLOSE OBS Studio (the plugin DLL cannot be replaced while OBS is open).%n%nThe installer does not include DistroAV or NDI: they are needed to receive the phone video and will be checked on the next step (without blocking the installation).
brazilianportuguese.FinishedLabel=O BDSM Link foi instalado. FECHE e abra o OBS Studio novamente e use o menu Exibir > Painéis (Docks) > BDSM Link. Não use ao mesmo tempo o script Python bdsm_link_obs.py (os dois enviam tally).
english.FinishedLabel=BDSM Link was installed. CLOSE and reopen OBS Studio, then use View > Docks > BDSM Link. Do not run the Python script bdsm_link_obs.py at the same time (both send tally).

[Code]
var
  PrereqPage: TWizardPage;
  PrereqNeeded: Boolean;

function IsPt: Boolean;
begin
  Result := ActiveLanguage = 'brazilianportuguese';
end;

function Tx(const Pt, En: String): String;
begin
  if IsPt then Result := Pt else Result := En;
end;

// Existe algum arquivo (nao pasta) que case com Pattern dentro de Dir?
function DirHasFile(const Dir, Pattern: String): Boolean;
var
  FindRec: TFindRec;
begin
  Result := False;
  if not DirExists(Dir) then Exit;
  if FindFirst(Dir + '\' + Pattern, FindRec) then
  begin
    try
      repeat
        if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) = 0 then
        begin
          Result := True;
          Break;
        end;
      until not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

// <PluginsRoot>\<qualquer pasta>\bin\64bit\distroav*.dll (ou obs-ndi*.dll, nome antigo do DistroAV)
function PluginsRootHasDistroAV(const PluginsRoot: String): Boolean;
var
  FindRec: TFindRec;
  Bin: String;
begin
  Result := False;
  if not DirExists(PluginsRoot) then Exit;
  if FindFirst(PluginsRoot + '\*', FindRec) then
  begin
    try
      repeat
        if ((FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0) and (FindRec.Name <> '.') and (FindRec.Name <> '..') then
        begin
          Bin := PluginsRoot + '\' + FindRec.Name + '\bin\64bit';
          if DirHasFile(Bin, 'distroav*.dll') or DirHasFile(Bin, 'obs-ndi*.dll') then
          begin
            Result := True;
            Break;
          end;
        end;
      until not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

function DetectObs: Boolean;
var
  Path: String;
begin
  Result := False;
  // O instalador oficial do OBS grava o caminho em HKLM\SOFTWARE\OBS Studio (valor padrao)
  if RegQueryStringValue(HKLM64, 'SOFTWARE\OBS Studio', '', Path) and DirExists(Path) then
    Result := True
  else if RegQueryStringValue(HKLM32, 'SOFTWARE\OBS Studio', '', Path) and DirExists(Path) then
    Result := True
  else if FileExists(ExpandConstant('{commonpf64}\obs-studio\bin\64bit\obs64.exe')) then
    Result := True;
end;

function DetectDistroAV: Boolean;
begin
  Result :=
    PluginsRootHasDistroAV(ExpandConstant('{commonappdata}\obs-studio\plugins')) or
    PluginsRootHasDistroAV(ExpandConstant('{userappdata}\obs-studio\plugins')) or
    // legado (obsoleto, mas ainda funciona): Program Files\obs-studio\obs-plugins\64bit
    DirHasFile(ExpandConstant('{commonpf64}\obs-studio\obs-plugins\64bit'), 'distroav*.dll') or
    DirHasFile(ExpandConstant('{commonpf64}\obs-studio\obs-plugins\64bit'), 'obs-ndi*.dll');
end;

function NdiRuntimeInDir(const Dir: String): Boolean;
begin
  Result := (Dir <> '') and DirExists(Dir) and
    (FileExists(Dir + '\Processing.NDI.Lib.x64.dll') or FileExists(Dir + '\v6\Processing.NDI.Lib.x64.dll'));
end;

function DetectNdiRuntime: Boolean;
begin
  // NDI_RUNTIME_DIR_V6 e a variavel que o DistroAV le (macro NDILIB_REDIST_FOLDER do SDK NDI);
  // aceitamos tambem um valor chamado NDILIB_REDIST_FOLDER por seguranca.
  Result :=
    NdiRuntimeInDir(GetEnv('NDI_RUNTIME_DIR_V6')) or
    NdiRuntimeInDir(GetEnv('NDILIB_REDIST_FOLDER')) or
    NdiRuntimeInDir(ExpandConstant('{commonpf64}\NDI\NDI 6 Runtime')) or
    DirExists(ExpandConstant('{commonpf64}\NDI\NDI 6 Runtime'));
end;

function IsObsRunning: Boolean;
var
  ResultCode: Integer;
begin
  Result := False;
  if Exec(ExpandConstant('{sys}\cmd.exe'), '/C tasklist /FI "IMAGENAME eq obs64.exe" | find /I "obs64.exe" >nul',
          '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    Result := (ResultCode = 0);
end;

function BuildPrereqText: String;
var
  Txt: String;
begin
  Txt := '';
  PrereqNeeded := False;

  if IsObsRunning then
  begin
    PrereqNeeded := True;
    Txt := Txt + Tx('[ATENÇÃO] O OBS Studio está ABERTO. Feche-o antes de continuar; senão a DLL do plugin pode não ser substituída.',
                    '[WARNING] OBS Studio is RUNNING. Close it before continuing; otherwise the plugin DLL may not be replaced.') + #13#10#13#10;
  end;

  if not DetectObs then
  begin
    PrereqNeeded := True;
    Txt := Txt + Tx('[FALTA] OBS Studio não foi encontrado (registro/pasta padrão). O plugin exige o OBS Studio 32.x (64 bits): https://obsproject.com/download',
                    '[MISSING] OBS Studio was not found (registry/default folder). The plugin requires OBS Studio 32.x (64-bit): https://obsproject.com/download') + #13#10#13#10;
  end;

  if not DetectDistroAV then
  begin
    PrereqNeeded := True;
    Txt := Txt + Tx('[FALTA] DistroAV (plugin NDI do OBS) não foi encontrado. Sem ele o OBS não recebe o vídeo do celular (o BDSM Link só faz telemetria e tally). Baixe em: https://github.com/DistroAV/DistroAV/releases/latest  (guia: https://github.com/DistroAV/DistroAV/wiki/1.-Installation)',
                    '[MISSING] DistroAV (the OBS NDI plugin) was not found. Without it OBS cannot receive the phone video (BDSM Link only does telemetry and tally). Download: https://github.com/DistroAV/DistroAV/releases/latest  (guide: https://github.com/DistroAV/DistroAV/wiki/1.-Installation)') + #13#10#13#10;
  end;

  if not DetectNdiRuntime then
  begin
    PrereqNeeded := True;
    Txt := Txt + Tx('[FALTA] NDI Runtime não foi encontrado (variável NDI_RUNTIME_DIR_V6 ou C:\Program Files\NDI\NDI 6 Runtime). O DistroAV precisa dele: https://ndi.video/tools/ (NDI Tools / NDI Runtime). Reinicie o OBS (e, se necessário, o Windows) depois de instalar.',
                    '[MISSING] NDI Runtime was not found (NDI_RUNTIME_DIR_V6 variable or C:\Program Files\NDI\NDI 6 Runtime). DistroAV needs it: https://ndi.video/tools/ (NDI Tools / NDI Runtime). Restart OBS (and Windows if needed) after installing.') + #13#10#13#10;
  end;

  Txt := Txt + Tx('Esta verificação NÃO bloqueia a instalação: você pode continuar e instalar os itens que faltam depois. O BDSM Link mostra no dock se o DistroAV está carregado.',
                  'This check does NOT block the installation: you can continue and install the missing items later. BDSM Link shows in the dock whether DistroAV is loaded.');
  Result := Txt;
end;

procedure InitializeWizard;
var
  Memo: TNewMemo;
begin
  PrereqPage := CreateCustomPage(wpWelcome,
    Tx('Pré-requisitos', 'Prerequisites'),
    Tx('Verificação do OBS Studio, do DistroAV e do NDI Runtime (apenas informativa).',
       'Check for OBS Studio, DistroAV and the NDI Runtime (informational only).'));
  Memo := TNewMemo.Create(PrereqPage);
  Memo.Parent := PrereqPage.Surface;
  Memo.Left := 0;
  Memo.Top := 0;
  Memo.Width := PrereqPage.SurfaceWidth;
  Memo.Height := PrereqPage.SurfaceHeight;
  Memo.ReadOnly := True;
  Memo.ScrollBars := ssVertical;
  Memo.WordWrap := True;
  Memo.Text := BuildPrereqText; // tambem define PrereqNeeded
end;

// Pagina so aparece se algo faltar ou o OBS estiver aberto.
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if (PrereqPage <> nil) and (PageID = PrereqPage.ID) then
    Result := not PrereqNeeded;
end;
