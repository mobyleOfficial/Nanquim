# Roadmap — ajustes do feedback (nov/2026)

Nove pedidos do feedback, agrupados em 4 entregas. Cada item diz **o que já existe no código**, **o que fazer** e **como saber que está pronto**.

Páginas legais (já escritas, no repo `mobyleofficial.github.io`, pasta `nanquim/`):

| Página | URL depois do deploy |
|---|---|
| Política de Privacidade (EN + PT-BR) | https://mobyleapps.com/nanquim/privacy-policy/ |
| Termos de Uso (EN + PT-BR) | https://mobyleapps.com/nanquim/terms/ |

> ⚠️ A política já descreve o **AdMob na galeria** e o **interruptor de estatísticas em Configurações › Privacidade**. Ela só pode ir ao ar junto com a versão que tem esses dois. Se a v0.3 sair sem anúncios, tire a seção "Ads/Anúncios" (e o parágrafo dos Termos) e recoloque na versão que trouxer os anúncios.

---

## Entrega 1 — v0.3: Confiança e Configurações (itens 3, 4, 5, 6, 2)

Tudo isso mexe na mesma tela (`ui/settings/SettingsScreen.kt`) e é pré-requisito do Play Console (política de privacidade obrigatória, porque o app usa Firebase).

### Nova seção "Sobre e feedback" em Configurações

Hoje o fim da tela tem só "Apoiar" e "Sobre" (`SettingsScreen.kt:217-218`). Passa a ser:

```
Sobre e feedback
  ★ Avaliar o Nanquim
  ↗ Compartilhar o Nanquim
  🐞 Relatar um erro
  💡 Sugerir um recurso
  Privacidade
     [x] Compartilhar estatísticas de uso e relatórios de falhas
     Opções de anúncios            (só aparece se o UMP exigir — ver Entrega 3)
  Política de Privacidade  ↗
  Termos de Uso  ↗
  Apoiar o projeto
  Sobre (versão, licenças)
```

### 3 + 4. Política de Privacidade e Termos acessíveis

- **Feito:** páginas escritas e bilíngues (ver tabela acima).
- **App:** duas opções separadas em Configurações que abrem a URL em Custom Tab / navegador (`Intent.ACTION_VIEW`). URLs em `strings.xml` como `translatable="false"`. Também um link para a política na tela **Sobre**.
- **Play Console:** colar a URL da política em *Conteúdo do app › Política de privacidade* e atualizar **Segurança dos dados**: Analytics (IDs do app, interações, diagnósticos, falhas) e, com a Entrega 3, ID de publicidade.
- **Interruptor de estatísticas** (a política promete): `AppSettings.analyticsEnabled` (padrão ligado) → `Telemetry.create(...)` já aceita `analyticsEnabled`; somar `FirebaseCrashlytics.setCrashlyticsCollectionEnabled(false)` quando desligado (hoje só o Analytics é desligado em `Telemetry.kt:126`).
- **Firebase Console:** conferir retenção do Analytics = 14 meses (o texto da política diz "até 14 meses").
- **Pronto quando:** as duas páginas abrem do app em PT e EN; desligar o interruptor zera eventos no DebugView do Firebase.

### 5. Avaliar o app

- **Botão em Configurações → abre a página da Play Store** (`market://details?id=com.mobyle.nanquim`, com fallback para `https://play.google.com/store/apps/details?id=com.mobyle.nanquim`). O Google recomenda **não** prender a In-App Review API a um botão: ela tem cota e pode não mostrar nada, o que pareceria um botão quebrado.
- **In-App Review API automática** (`com.google.android.play:review-ktx`), num momento bom: depois da 3ª exportação/compartilhamento de arte ou após ~5 sessões com uma arte salva, no máximo uma vez a cada 90 dias, nunca durante o desenho. Contadores em `SharedPreferences`.
- **Pronto quando:** testado com *internal app sharing* (a review só aparece em build instalado pela Play).

### 6. Compartilhar o app

- `Intent.ACTION_SEND` + `Intent.createChooser`, texto traduzido: "Estou desenhando no Nanquim, app gratuito de desenho e pintura: https://play.google.com/store/apps/details?id=com.mobyle.nanquim".
- Tirar o marcador "Compartilhe…" da tela Apoiar (`SupportScreen.kt`) e trocar por esse mesmo botão.
- Opcional: link com `utm_source=app_share` para medir no Play Console.

### 2. Relatar erros e sugerir recursos (GitHub)

- **No repo:** criar `.github/ISSUE_TEMPLATE/bug_report.yml` e `feature_request.yml` (formulários do GitHub com campos: o que aconteceu, passos, aparelho, versão) e `config.yml` com link para o e-mail como alternativa.
- **No app:** "Relatar um erro" abre `https://github.com/mobyleOfficial/Nanquim/issues/new?template=bug_report.yml&version=…&device=…` com versão do app, modelo, Android e renderizador **pré-preenchidos** (dados que o `DiagnosticsCollector` já reúne). "Sugerir um recurso" abre o template de sugestão.
- **Sem conta no GitHub:** um diálogo antes oferece "Abrir no GitHub (precisa de conta)" ou "Enviar por e-mail" (`ACTION_SENDTO mailto:support@mobyleapps.com` com o mesmo texto e, opcionalmente, o JSON de diagnóstico anexado).
- Aviso no diálogo: "O relato no GitHub fica público" (a política já diz isso).
- **Pronto quando:** a issue chega com os campos preenchidos e com o label certo (`bug` / `enhancement`).

---

## Entrega 2 — v0.3.x: Saída do app e estado (itens 7, 8)

### 7. Confirmação ao sair com o botão voltar

- **Hoje:** o `BackHandler` em `MainActivity.kt:451` só age se `navigator.canGoBack` ou se há painel aberto. Na Galeria (raiz) o voltar fecha o app direto.
- **Fazer:** quando `navigator.current == Gallery` e nada aberto, interceptar e mostrar `AlertDialog` "Sair do Nanquim?" — [Cancelar] [Sair]. "Sair" chama `finish()`. Com uma caixa "Não perguntar de novo" (preferência), para quem achar chato.
- **Na tela do desenho** o voltar continua levando à Galeria (já salva; não confirmar aí).
- **Atenção ao Android 14+ (back preditivo):** se um dia `android:enableOnBackInvokedCallback="true"` for ligado, interceptar o voltar da raiz desliga a animação de "voltar para a home". É aceitável, mas só registre o `BackHandler` quando o diálogo estiver habilitado nas preferências.
- **Pronto quando:** teste instrumentado: voltar na Galeria mostra o diálogo; Cancelar mantém; Sair fecha.

### 8. Preservar o estado ao sair

- **Já existe:** salvamento automático no `onStop` (`MainActivity.kt:273`, `persistence.saveNow`) e, ao reabrir o app, a última arte aberta volta (`Gallery.restoreLastOpen`, `ArtworkOpenSource.Restore`).
- **Lacunas a fechar:**
  1. Restaurar também **zoom/posição da tela, rotação, ferramenta e pincel ativos, cor atual** e o **quadro da animação** — guardar junto com `KEY_OPEN_ARTWORK` ao salvar.
  2. Restaurar **qual tela estava aberta** (Galeria × Configurações × arte), não só a arte.
  3. Sair pelo diálogo do item 7 deve gravar tudo **antes** do `finish()` (aguardar `saveNow`).
  4. Process death: testar com `adb shell am kill com.mobyle.nanquim` com o app em segundo plano e com "Não manter atividades".
- **Pronto quando:** desenhar, dar zoom, trocar pincel, matar o processo e reabrir → mesmo lugar, mesmo pincel, mesma visão.

---

## Entrega 3 — v0.4: Anúncios só na Galeria (item 9)

### Decisão de produto antes de codar

- A ficha da Play (`store-listing/listing.md`) e o README dizem **"Grátis, sem anúncios"**. Com anúncios, isso precisa mudar na ficha (PT e EN), nas imagens que mencionarem e no README, senão a Play pode recusar por metadados enganosos.
- Considerar "Remover anúncios" como compra única (Play Billing) ou deixar o botão "Apoiar" cumprir esse papel. Fica para depois; não bloqueia.

### Formato (não intrusivo)

- **Um banner adaptativo ancorado no rodapé da Galeria** (AdMob *anchored adaptive banner*), **ou** um **anúncio nativo** que ocupa uma célula da grade a cada ~12 artes, com o selo "Anúncio" e o mesmo tamanho dos cards. Recomendação: começar com o banner (mais simples, menos risco de clique acidental na grade).
- **Nunca:** intersticiais, anúncios de abertura, vídeo com recompensa, anúncios na tela de desenho, no editor de pincéis, nos painéis ou em Configurações.
- Não carregar anúncio enquanto a arte abre (evitar disputar CPU/memória com o motor); pausar/destruir o `AdView` ao entrar na tela de desenho.
- Esconder o banner quando a Galeria está vazia ou durante seleção múltipla/arrastar para pilhas, para não atrapalhar o recurso.

### Técnico

- Dependências: `com.google.android.gms:play-services-ads` e `com.google.android.ump:user-messaging-platform`.
- **Consentimento (UMP):** pedir no início, antes de carregar anúncios; em Configurações › Privacidade, "Opções de anúncios" chama `ConsentInformation.privacyOptionsRequirementStatus` / `showPrivacyOptionsForm` (a política já cita essa opção).
- `APPLICATION_ID` do AdMob e IDs dos blocos por `gradle.properties`/`local.properties` (o repo é público); builds `dev` e `benchmark` usam IDs de teste do Google e o benchmark desliga anúncios.
- Manifesto passa a ter `com.google.android.gms.permission.AD_ID` (vem com o SDK) → declarar no Play Console (*ID de publicidade: sim, para publicidade*).
- `CREDITS.md` / `tools/check_licenses.py`: os SDKs de anúncio são proprietários — registrar e ajustar a verificação de licenças.
- `app-ads.txt` na raiz de `mobyleapps.com` (repo do site) com a linha do AdMob, e o site cadastrado como site do desenvolvedor na Play.
- Classificação etária / público-alvo: manter "não direcionado a crianças" (bate com a política) e configurar o AdMob com conteúdo máximo **G/PG**.

### Pronto quando

- Banner aparece só na Galeria, com anúncios de teste; nada carrega na tela de desenho (conferir no Logcat).
- Formulário de consentimento aparece com geografia de teste EEA (`ConsentDebugSettings`).
- Rolagem e abertura de arte na Galeria sem queda de FPS mensurável (`benchmarks/`).

---

## Entrega 4 — v0.4.x: Onboarding melhor (item 1)

- **Já existe:** boas-vindas no primeiro uso (`help/Help.kt`, `ui/help/HelpViews.kt`) com 5 cartões — Navegação, Desfazer, Pincéis, Camadas, Cor — e dicas de ferramenta na primeira vez que cada uma é usada; em Configurações › Ajuda dá para rever as boas-vindas, resetar dicas e reinstalar exemplos.
- **O feedback provavelmente não viu isso** (pode ter sido pulado). Melhorias:
  1. Cartões novos com os diferenciais: **QuickShape** (segure o traço), **Gestos** (2 dedos desfaz, 3 refaz, pinça), **Animação/Timelapse** e **Exportar/compartilhar**.
  2. Indicador de progresso (●○○○), botão "Pular" visível e "Começar a desenhar" no último cartão que já abre uma tela nova.
  3. Animações curtas (Lottie ou Compose) em vez de imagens estáticas onde ajuda (gestos).
  4. Na primeira abertura de uma arte, um "tour" leve de 3 balões apontando barra de ferramentas, pincel/tamanho/opacidade e camadas.
  5. Manter "Rever introdução" em Configurações › Ajuda.
- **Medir:** evento `welcome_completed` / `welcome_skipped` (com o índice do cartão) no Analytics — respeitando o interruptor da Entrega 1.
- **Pronto quando:** teste de UI percorre o onboarding completo; textos em EN e PT-BR.

---

## Ordem e checklist

| # | Item do feedback | Entrega | Status |
|---|---|---|---|
| 3 | Política de privacidade e termos | 1 | Páginas escritas · falta publicar o site |
| 4 | Acesso em Configurações | 1 | A fazer |
| 5 | Avaliar o app | 1 | A fazer |
| 6 | Compartilhar o app | 1 | A fazer |
| 2 | Relatar erro / sugerir (GitHub) | 1 | A fazer |
| 7 | Confirmar saída no voltar | 2 | A fazer |
| 8 | Preservar estado | 2 | Parcial (arte reabre; falta visão/ferramenta/tela) |
| 9 | Anúncios só na Galeria | 3 | A fazer · exige mudar a ficha "sem anúncios" |
| 1 | Onboarding | 4 | Parcial (5 cartões + dicas existem) |

Strings novas sempre em `values/strings.xml` **e** `values-pt-rBR/strings.xml`.
