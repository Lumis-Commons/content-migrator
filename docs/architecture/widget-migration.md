# Migração de widgets embutidos em HTML — arquitetura

Documento para o agente que implementa o handler `widget_repository` (DependencyHandler do PAT002), o
modal de mapeamento de widgets e os testes deles. Leia como instrução: cada regra abaixo diz o que
fazer, com a evidência no código do Lumis Portal 18.1.1 (caminhos relativos a `Lumis_PortalJava/`) ou
no módulo (caminhos relativos a `br.com.lumis.commons.contentmigrator/`).

Contratos adotados: PAT001 r1 (persistência e mapeamento) e PAT002 r1 (SPI e regras de dependência).
São os rótulos dos dois contratos de projeto do módulo: o PAT001 está materializado nos
`databasedefinition.xml` e nos DAOs e é verificado por `PersistenceContractTest`; o PAT002, em
`DependencyHandler` e `DependencyRules`, é verificado por `SpiRulesTest`.
Este documento não redefine tabelas nem assinaturas; o que falta neles está em
[Propostas de contrato](#propostas-de-contrato).

Fora do escopo: o engine (laço de itens, lock, heartbeat) e a tela do modal de mapeamento; aqui só a
consulta que o alimenta.

## Modelo de dados

- **Widget** — conteúdo do serviço `lumis.service.pagepersonalization` (tipo `lum_content`,
  `pagepersonalization/src/java/lumis/service/pagepersonalization/servicedefinition.xml:4`), source
  `widget` do tipo `contentTable` sobre `lum_PPWidget`, com `multiLanguage`, `workflow` e
  `versioning maxVersions="1"`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/douidefinition.xml:8-28`).
  A linha `lum_PPWidget` tem `id` (= `lum_ContentVersion.itemId`), `name`, `description`, `icon` (mídia)
  e `interfaceInstanceId`, a **interface base** do widget
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/database/databasedefinition.xml:3-39`,
  entidade em `pagepersonalization/src/java/lumis/service/pagepersonalization/bean/Widget.java:26-45`).
  A interface base é uma interface instance órfã, de dono
  `PagePersonalizationInterfaceInstanceOwner`
  (`portal/src/java/lumis/service/pagepersonalization/doui/serviceinterfaceinstance/PagePersonalizationInterfaceInstanceOwner.java:22-45`).
- **Repositório de widgets** — a instância de conteúdo depende dele pelo tipo `widget_repository`
  (`portal/src/java/lumis/portal/serviceinstance/ServiceInstanceDependencyConfig.java:51`), criado
  automaticamente com a instância mais próxima na árvore de canais
  (`doui/src/java/lumis/doui/service/DouiService.java:1340-1353`).
- **lum_PPWidgetInstance** — tabela única de instâncias com discriminador `type`; `F` é
  `HtmlFieldWidgetInstance`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/bean/WidgetInstance.java:38-51`,
  `pagepersonalization/src/java/lumis/service/pagepersonalization/bean/HtmlFieldWidgetInstance.java:27-41`).
  Colunas: `id`, `widgetContentId` (**contentId** do widget), `interfaceInstanceId` (única),
  `fieldServiceInstanceId`, `fieldSourceId`, `createdDateTime`, `type`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/database/databasedefinition.xml:77-157`).
- **Interface da instância** — o construtor `WidgetInstance(widgetContentId)` cria, na transação
  corrente e como sistema, uma interface instance órfã **derivada** da interface base do widget, lida
  da versão ativa do **primeiro** idioma do conteúdo
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/bean/WidgetInstance.java:94-122`).
  A derivada herda `serviceInstanceId` e `serviceInterfaceId` da base
  (`portal/src/java/lumis/portal/serviceinterfaceinstance/ServiceInterfaceInstanceManager.java:819-841`);
  propriedades e preferências próprias da derivada são a configuração do embed.
- **lum_PPWidgetInstanceItemId** — `(id, widgetInstanceId, itemId)`, único por par, FK em cascata para
  `lum_PPWidgetInstance`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/database/databasedefinition.xml:188-229`);
  é a coleção `fieldItemIds`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/bean/HtmlFieldWidgetInstance.java:37-41`).
  Liga a instância aos itemIds (versões) dos conteúdos que a embutem.
- **Remoção** — só por `WidgetInstance.remove()`, que apaga a linha e a interface derivada; `@PreRemove`
  recusa outro caminho
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/bean/WidgetInstance.java:157-192`).
- **Limpeza de órfãos** — o clock diário `DeleteUnusedHtmlFieldWidgetInstance`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/servicedefinition.xml:27-30`) apaga
  instâncias sem itemIds criadas há mais de 2 h
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/clock/DeleteUnusedHtmlFieldWidgetInstance.java:25-33`).
- **Uso de um widget** — `WidgetUsedByDataProvider` lista os conteúdos por `widgetContentId` e
  `fieldItemIds`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/doui/dataprovider/WidgetUsedByDataProvider.java:129-134`).

## Formato do embed e áreas

- **Embed gravado** — `<div class="lum-html-widget-embedded" data-widget-interfaceinstanceid="<ii>"
  id="lum-html-widget-embedded-<instanceId>"></div>`. O atributo `data-widget-interfaceinstanceid` é
  opcional no valor gravado: o editor o insere
  (`portal/src/www/lumis/tool/ckeditor/customplugins/lumisWidgetRepository/plugin.js:41`), mas
  `decodeEditorFormatToHtml` grava só classe e `id`
  (`doui/src/java/lumis/doui/control/htmleditor/HtmlEditorControl.java:118-120`,
  `doui/src/java/lumis/doui/control/htmleditor/HtmlEditorControl.java:326-332`), e o clone nativo o
  regrava
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:893-900`).
- **Detecção** — use o mesmo padrão do portal, `WIDGET_ENCODE_PATTERN`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:115-116`):
  `<div[^>]+?id="lum-html-widget-embedded-(\w{32})[^>]++><\/div>`. O grupo 1 é o id da instância. O
  render (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:319-366`)
  e os hooks usam esse padrão; o que ele não casa o portal não renderiza nem vincula.
- **Áreas JSON** — a partir da 17, o valor pode ser um array JSON de objetos
  `{"lumTargetRenderArea": <nome>, "lumHtmlContent": <html>}`
  (`doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:48-51`). Detecte com
  `isContentSplitIntoRenderAreas`, leia com `parseContent` (valor sem áreas vira uma área de nome nulo)
  e regrave com `processAreasHtml`, que serializa de volta com `buildContentValue`
  (`doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:150-220`,
  `doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:291-308`).
  Nunca rode o regex sobre o JSON bruto: as aspas estão escapadas dentro de `lumHtmlContent`.

HTML antes (origem, gravado pelo spike, áreas `main` e `side`, duas instâncias do widget A):

```json
[{"lumTargetRenderArea":"main","lumHtmlContent":"<p>antes<\/p><div class=\"lum-html-widget-embedded\" data-widget-interfaceinstanceid=\"FF808081A144D52A01A144D5673400B3\" id=\"lum-html-widget-embedded-FF808081A144D52A01A144D5673D00B5\"><\/div><p>depois<\/p>"},
 {"lumTargetRenderArea":"side","lumHtmlContent":"<div class=\"lum-html-widget-embedded\" data-widget-interfaceinstanceid=\"FF808081A144D52A01A144D5675700BB\" id=\"lum-html-widget-embedded-FF808081A144D52A01A144D5675D00BD\"><\/div>"}]
```

HTML depois (destino, gravado e relido de `lum_News.content`, duas instâncias novas do widget B):

```json
[{"lumTargetRenderArea":"main","lumHtmlContent":"<p>antes<\/p><div class=\"lum-html-widget-embedded\" data-widget-interfaceinstanceid=\"FF808081A144D52A01A144D5688900F3\" id=\"lum-html-widget-embedded-FF808081A144D52A01A144D5689200F5\"><\/div><p>depois<\/p>"},
 {"lumTargetRenderArea":"side","lumHtmlContent":"<div class=\"lum-html-widget-embedded\" data-widget-interfaceinstanceid=\"FF808081A144D52A01A144D5689D00F7\" id=\"lum-html-widget-embedded-FF808081A144D52A01A144D568A500F9\"><\/div>"}]
```

Só mudam o sufixo do `id` e o valor de `data-widget-interfaceinstanceid`; texto, ordem, nomes de área
e demais atributos ficam iguais. Num embed sem `data-widget-interfaceinstanceid`, insira o atributo.

## Ciclo de vida nativo de HtmlFieldWidgetInstance

1. **Seleção no editor** — `WidgetSelectProcessActionHandler` chama
   `createHtmlFieldWidgetInstance(widgetItemId)`
   (`pagepersonalization/src/java/lumis/service/pagepersonalization/WidgetSelectProcessActionHandler.java:77-78`),
   que persiste a instância (do contentId do widget) e **comita numa transação isolada**, sem itemIds
   (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:376-401`).
   Se o conteúdo não for salvo, o clock a apaga depois de 2 h.
2. **preAdd / preUpdate** — `HtmlDataType` chama `cloneWidgetsIfNeeded` antes de gravar e troca o
   parâmetro pelo HTML devolvido
   (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1088-1111`,
   `doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:967-992`,
   `doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1503-1515`). Para cada embed de cada
   área, se a instância já está ligada a itemIds de **outro** ContentLocale (ou de outra linha de
   tabela), persiste `new HtmlFieldWidgetInstance(widgetContentId)` **na transação corrente**, copia
   propriedades customizadas e preferências compartilhadas da interface antiga e reescreve `id` e
   `data-widget-interfaceinstanceid`
   (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:734-928`,
   regra em `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:850-871`,
   cópia em `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:936-949`,
   vínculos por locale em `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:960-993`).
   Instância sem itemIds não é clonada. Instância inexistente faz o add falhar com
   `STR_WIDGET_INSTANCE_DONT_EXISTS`
   (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:833-848`).
   O clone nativo sempre aponta para o **mesmo** widget: não troca de repositório.
3. **postAdd** — `processWidgetInstaceInHtmlAdded` acrescenta o novo itemId a `fieldItemIds` e grava
   `fieldSourceId` e `fieldServiceInstanceId`; o mesmo id repetido no HTML ganha instância nova
   (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:887-901`,
   `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:1002-1056`).
4. **preUpdate (diferença)** — `processWidgetInstaceInHtmlUpdate` remove instâncias que saíram do HTML e
   liga as novas ao itemId atualizado
   (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:993-1008`,
   `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:625-723`).
5. **preDelete** — `processWidgetInstaceInHtmlDelete` tira os itemIds apagados e remove a instância que
   ficou sem nenhum (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:856-866`,
   `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:591-615`).

Todos esses hooks só rodam quando o conteúdo é gravado pela source (`addData`, `updateData`,
`deleteDataByItemIds`); gravar a tabela por SQL não vincula nem limpa instâncias.

## Algoritmo de migração

Para cada item de conteúdo, na ordem do PAT002 (mídia, documento, widget), para cada campo `html`:

1. **Repositórios** — leia a dependência `widget_repository` da instância de origem e da de destino.
   - Mesma instância de repositório: **não reescreva**. Passe o HTML como está; o
     `cloneWidgetsIfNeeded` nativo clona cada instância para o novo ContentLocale, com propriedades e
     preferências, e o postAdd liga o clone ao novo itemId (provado no spike). Sem linha de mapa
     (PAT002).
   - Instâncias diferentes: siga os passos abaixo. DependencyRules já garante que ambos têm repositório
     do mesmo tipo (PAT002).
2. **precheck(item)**, sem gravar nada — para cada área (`parseContent`) e cada embed
   (`WIDGET_ENCODE_PATTERN`):
   1. `find(WidgetInstance.class, id)`; instância inexistente: marque o embed como pendente de remoção
      (ver [Falhas e bloqueios](#falhas-e-bloqueios)) e siga.
   2. Leia `widgetContentId` e procure em `lum_cmig_MigWidgetMap` a linha
      `(originWidgetContentId, destRepoInstanceId)`.
   3. Sem linha, ou com destino que já não existe no repositório de destino (conteúdo da source
      `widget` com versão ativa e linha `lum_PPWidget`): lance
      `MigrationBlockedException(WIDGET_MAPPING_REQUIRED, originItemId, widgetContentId)`. O engine
      reverte o item e grava `blockingOriginItemId` e `blockingWidgetContentId` (PAT001).
3. **Reescrita**, na transação do item, depois do precheck e antes de `addData`/`updateData` — com
   `processAreasHtml`, para cada embed:
   1. `new HtmlFieldWidgetInstance(destWidgetContentId)` e `getEntityManager().persist` — **nunca**
      `createHtmlFieldWidgetInstance`, que comita à parte e deixaria órfãos num rollback. A interface
      derivada e a instância entram na transação do item; o rollback as desfaz (provado no spike).
   2. Se a interface base do widget de origem e a do destino têm o mesmo `serviceInterfaceId`, copie
      as propriedades customizadas (`getCustomProperties`/`setCustomProperties`) e as preferências
      compartilhadas (`copySharedInterfaceInstancePreferences`) da interface do embed de origem para a
      nova, como faz o clone nativo. Com interfaces diferentes, não copie: o embed herda a configuração
      do widget de destino.
   3. Troque o sufixo do `id` pelo id da nova instância e o valor de `data-widget-interfaceinstanceid`
      pela nova interface (insira o atributo se faltar). Não reparse o embed com outro serializador:
      substitua só os dois atributos.
4. **Gravação** — `addData`/`updateData` com o HTML reescrito. A nova instância não tem itemIds, então
   `cloneWidgetsIfNeeded` a mantém; o postAdd (ou o preUpdate) a liga ao itemId do destino, com
   `fieldServiceInstanceId` e `fieldSourceId` do destino. O HTML gravado é idêntico ao reescrito
   (provado no spike).

A origem não é tocada: o handler só lê instâncias, widgets e interfaces da origem.

### Consulta do modal

Opções do modal para o repositório de destino — conteúdos da source `widget` com o nome da versão
ativa, um por contentId:

```sql
select c.id, w.name
  from ContentVersion cv inner join cv.contentLocale cl inner join cl.content c, Widget w
 where cv.itemId = w.id and cl.activeVersion.id = cv.id
   and c.serviceInstanceId = :destRepository and c.sourceId = 'widget'
 order by w.name, c.id
```

Implementação de referência em `WidgetFixture.listWidgets`
(`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/WidgetFixture.java:257-274`). O
widget bloqueante da origem é mostrado pelo `name` da linha `lum_PPWidget` de
`blockingWidgetContentId` (`WidgetFixture.getWidget`,
`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/WidgetFixture.java:247-251`).

## Mapeamento e reuso

- **Chave: contentId do widget**, não itemId. Motivos:
  - a instância guarda `widgetContentId` e o próprio portal resolve a interface base pela versão
    ativa do primeiro idioma
    (`pagepersonalization/src/java/lumis/service/pagepersonalization/bean/WidgetInstance.java:94-109`);
  - o itemId muda a cada versão (`versioning maxVersions="1"`) e é diferente por idioma
    (`multiLanguage`), `pagepersonalization/src/java/lumis/service/pagepersonalization/douidefinition.xml:19-25`;
    um mapa por itemId quebraria a cada publicação do widget e duplicaria linhas por idioma;
  - o render e o editor também partem do contentId
    (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:205-223`).
- Uma linha de `lum_cmig_MigWidgetMap` por (widget de origem, repositório de destino), como no PAT001;
  vale para todos os idiomas e versões do widget e para todas as migrações que usam o mesmo
  repositório de destino. Widgets nunca são clonados: o usuário escolhe um widget existente no destino.
- O mapa é de widget, não de instância: cada embed copiado ganha instância nova, mesmo que dois
  conteúdos de origem compartilhem o widget. Instâncias não entram em `lum_cmig_MigItemMap`.

## Modo atualização

- O destino já existe (linha CONTENT do MigItemMap): rode o mesmo precheck e a mesma reescrita e chame
  `updateData`. As instâncias novas entram pelo `addNewWidgets` nativo e as do HTML anterior do
  destino, ausentes do novo, saem pelo `removeDeletedWidgets`
  (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:628-635`).
- Não reaproveite instâncias do destino: uma reexecução troca as instâncias do embed (e suas
  interfaces) por novas. O custo é rotatividade de ids; o ganho é o mesmo caminho do add, sem
  heurística de casamento por posição.
- Mapeamento alterado entre execuções (outro widget de destino) é aplicado na próxima atualização.

## Falhas e bloqueios

| Situação | Ação |
|---|---|
| Widget de origem sem linha em `lum_cmig_MigWidgetMap` para o repositório de destino | `WIDGET_MAPPING_REQUIRED` no precheck, sem gravação; `blockingWidgetContentId` = contentId do widget de origem. |
| Widget de destino mapeado apagado | Mesmo bloqueio; o modal mostra o mapeamento inválido para nova escolha. |
| Embed cuja instância não existe na origem | O portal não o renderiza e o add nativo falharia; remova o embed do HTML e grave aviso na linha do MigItemMap. |
| Interfaces base com `serviceInterfaceId` diferentes | Grave sem copiar propriedades e preferências; aviso. |
| Falha posterior no item (outro handler, `addData`) | Rollback desfaz instância e interface derivada; nada a limpar. |
| Mesmo repositório na origem e no destino | Sem reescrita; o clone nativo resolve. |

Mais de um widget sem mapeamento no mesmo item: o precheck lança no primeiro em ordem de documento; os
demais aparecem nas próximas tentativas (ver P3).

## Riscos

- `copySharedInterfaceInstancePreferences` está em `ManagerFactoryInternal`
  (`portal/src/java/lumis/portal/preferences/PreferencesManager.java:775`), API interna; uma troca de
  versão do portal pode quebrar a compilação. É o mesmo método do clone nativo.
- Caches do portal (`customPropertiesCache`,
  `portal/src/java/lumis/portal/serviceinterfaceinstance/ServiceInterfaceInstanceManager.java:1452-1456`)
  podem guardar entradas de interfaces revertidas num rollback; ids novos não colidem, então o efeito é
  só memória até a expiração.
- O spike não cobriu embed sem `data-widget-interfaceinstanceid` nem `updateData`; o teste do estágio 2
  (`WidgetMigrationTest`) deve cobrir os dois.
- Versões antigas do destino em conteúdo versionado podem citar instâncias que o `removeDeletedWidgets`
  apagou numa atualização; é o comportamento nativo de qualquer edição que remove um widget.
- O construtor de `WidgetInstance` impersona o sistema e adiciona a interface com auditoria: cada embed
  migrado gera um registro de auditoria de interface instance.
- Widget de destino sem idioma ativo ou sem `lum_PPWidget` faz o construtor lançar
  `NullPointerException`; o precheck evita isso conferindo `lum_PPWidget` antes.

## Propostas de contrato

- **P1 (PAT001)** — fixar as colunas de `lum_cmig_MigWidgetMap` como `originRepoInstanceId`,
  `originWidgetContentId`, `destRepoInstanceId` e `destWidgetContentId`, todas contentIds de widget (não
  itemIds), com chave única `(originWidgetContentId, destRepoInstanceId)`.
- **P2 (PAT002)** — explicitar que o handler `widget_repository` não clona widgets nem grava
  `lum_cmig_MigItemMap`; no desfazer não informa clones, e as instâncias criadas saem com o conteúdo de
  destino pelo `preDelete` nativo da source.
- **P3 (PAT001)** — opcional: `errorMessage` de `WIDGET_MAPPING_REQUIRED` lista todos os contentIds de
  widget sem mapeamento do item, para o modal resolvê-los numa só vez; `blockingWidgetContentId`
  continua sendo o primeiro.
- **P4 (PAT002)** — classificar embed com instância inexistente como aviso com remoção do embed, não
  como falha do item.

## Evidências

- Spike `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:156-234`:
  dois repositórios de widget, widgets A e B com interfaces do mesmo tipo, conteúdo de origem com dois
  embeds de A em duas áreas JSON (montado em
  `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:112-147`).
  Depois de `precheck` e `rewrite`
  (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:429-493`)
  e `addData` na mesma transação, prova: HTML gravado igual ao reescrito, mesmas áreas e texto, duas
  instâncias novas do widget B ligadas só ao novo itemId, com a interface derivada da base de B,
  propriedade customizada copiada, e origem intacta.
- Rollback `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:236-277`:
  falha depois do `addData` não deixa instância, interface nem conteúdo no destino.
- Bloqueio `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:279-310`:
  sem mapeamento, ou com destino inexistente, o precheck lança com itemId e contentId de origem e nada é
  gravado.
- Mesmo repositório `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:312-347`:
  HTML de origem gravado sem reescrita; o clone nativo cria instâncias novas do widget A ligadas ao
  novo itemId, com a propriedade copiada.
- Primeira execução: falhou só a asserção de origem intacta do caso de mesmo repositório, porque os
  clones nativos também são instâncias do widget A; corrigida a asserção
  (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/WidgetLinkSpikeTest.java:354-381`).
- Fixture `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/WidgetFixture.java:79-164`:
  interface base órfã, widget publicado e instância criada como no editor; `addData` e `track` pela
  source (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/WidgetFixture.java:182-218`).
- Validação: `VAL-SPIKE-WIDGET` (`bash test/run-tests.sh WidgetLinkSpikeTest`) e `VAL-ARCH-WIDGET`
  (`python3 test/check_architecture.py docs/architecture/widget-migration.md`).

## Contexto do handler depois dos DTOs

Nota de 2026-10-18. O código do módulo passou a agrupar os valores repetidos em dois DTOs do pacote
`br.com.lumis.commons.contentmigrator.service.contentmigration.dto`, e nenhum método ou construtor de `src` recebe mais de 5
parâmetros. O que muda para quem lê este documento:

- `MigrationScope` reúne sessão, transação, conexão, `serviceId`, instâncias de origem e destino,
  sources, modo de atualização e a lista de warnings do item. Ele nasce de `MigrationScope.builder()`
  em `ItemProcessor.processOnce`, uma vez por transação de item; uma nova tentativa cria outro.
- `VersionCopy` descreve uma versão de origem a gravar (linha, versão e ids de destino). Para cada
  versão, `ItemProcessor.copySingleVersion(scope, dependencies, version)` chama
  `ContentCopier.buildData(scope, dependencies, version)`, grava e chama
  `ContentCopier.applyAfterWrite(scope, dependencies, originVersion, destinationVersion)`.
- O handler recebe um `DependencyContext(MigrationScope scope, RepositoryPair repositories, FileCloner fileCloner)`.
  `ItemDependencies.create(scope, fileCloner)`, em `copy`, monta um contexto por handler para o item
  inteiro (prechecks, versões e associações). Os getters não mudaram de nome nem de semântica, e os
  warnings do contexto, deduplicados, vão para a lista do escopo, que é gravada no mapa do item.
- No desfazer, `MigrationUndoer.undoRepositoryItem` monta um escopo sem sources, e
  `undoExistingRepositoryClone(scope, item, handler)` cria o contexto com `FileCloner` nulo.
- O handler de widget não ganhou métodos novos: lê os repositórios e o modo de atualização pelos
  mesmos getters do contexto.
