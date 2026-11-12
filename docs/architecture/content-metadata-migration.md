# Migração de metadados de conteúdo — arquitetura

Documento para o agente que implementa, no engine, a gravação de um ContentLocale de origem no
destino com seus metadados: workflow (estado e assignedTo), par de versões publicada e ativa,
categorias, associações, tags, comentários editoriais e autoria. Caminhos relativos a
`Lumis_PortalJava/` (portal) ou a `br.com.lumis.commons.contentmigrator/` (módulo).

Contratos adotados: PAT001 r1 (persistência e mapeamento) e PAT002 r1 (SPI e regras de dependência).
São os rótulos dos dois contratos de projeto do módulo: o PAT001 está materializado nos
`databasedefinition.xml` e nos DAOs e é verificado por `PersistenceContractTest`; o PAT002, em
`DependencyHandler` e `DependencyRules`, é verificado por `SpiRulesTest`.
Este documento não redefine tabelas nem assinaturas; o que falta neles está em
[Propostas de contrato](#propostas-de-contrato).

Fora do escopo: o código do engine e o do handler `categorization_repository` (este documento dá a
regra que eles seguem e o spike prova as partes de risco).

## Modelo de dados

### Workflow por instância

- O workflow é da **instância de serviço**: `getServiceInstanceWorkflow` lê o id em
  `lum_WorkflowServiceInstance` (`content/src/java/lumis/content/workflow/WorkflowManager.java:493-503`)
  e a source o devolve em `getSourceWorkflow`
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:485-491`). PAT002 já rejeita a
  criação com workflows diferentes entre origem e destino; com o mesmo id, os estados e transições
  são os mesmos (definição única, p. ex. `content/src/java/lumis/workflow/standard/workflowdefinition.xml:3-190`).
- O id de cada transição é prefixado pelo estado inicial (`authoring.approve`,
  `content/src/java/lumis/content/workflow/WorkflowTransitionConfig.java:55-65`); os parâmetros de
  transição do `WorkflowController` usam esse id
  (`content/src/java/lumis/content/workflow/WorkflowController.java:79-94`).
- Os papéis de cada principal são **por instância** (`lum_WorkflowPermission`, gravada em
  `content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:381-450`, lida em
  `content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:522-560`). O mesmo usuário pode ter
  papéis diferentes na origem e no destino.

### lum_WorkflowMetaData

- Uma linha por ContentLocale: `workflowMetaDataId`, `contentLocaleId`, `stateId`, `assignedTo`
  (`content/src/java/lumis/content/workflow/ContentWorkflowMetaData.java:25-30`; SQL em
  `content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:602`,
  `content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:679` e
  `content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:728`). Não há versão por linha: o
  estado vale para o ContentLocale inteiro.
- **Criação automática**: o `@PostPersist` do ContentLocale cria a linha no estado inicial, com
  `assignedTo` = usuário da sessão (`content/src/java/lumis/content/core/ContentLocale.java:826-847`,
  `content/src/java/lumis/content/workflow/WorkflowManager.java:665-691`). Antes de criar, exige que
  o usuário tenha papel no estado inicial, nos escopos all ou assignedTo; senão `AccessDeniedException`
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:675-679`).
- **Remoção automática**: o `@PreRemove` apaga a linha (`content/src/java/lumis/content/core/ContentLocale.java:849-854`).
- **Não há API pública para gravar o estado**. O manager só muda estado por `executeAction`, que exige
  transição do estado corrente, permissão do usuário na transição e do novo assignedTo no estado final,
  roda os action handlers e publica `WorkflowActionExecutedEvent`
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:725-862`). O DAO que grava direto
  (`IWorkflowDao.update`) é interno. O precedente de acesso SQL direto à tabela no próprio produto é a
  serialização de instância (`content/src/java/lumis/content/service/serialization/ContentServiceSerializationManager.java:642-644`).
  A migração grava com `update lum_WorkflowMetaData set stateId=?, assignedTo=? where contentLocaleId=?`
  na conexão da transação do item, depois do flush — mesmo SQL do DAO
  (`content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:721-746`), só que por `contentLocaleId`.

### Permissão de edição por estado

- `hasEditPermission`: sem workflow ou sem linha de metadado, libera; senão o usuário precisa de papel
  no estado corrente com escopo all, ou com escopo assignedTo **se** o conteúdo estiver atribuído a
  ninguém, a ele ou a um grupo dele
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:872-902`,
  `content/src/java/lumis/content/workflow/WorkflowManager.java:330-345`).
- Só o usuário de sistema escapa da checagem
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:259-263`,
  `content/src/java/lumis/content/workflow/WorkflowManager.java:312-321`).
- Quem checa:
  - `updateData`: `WorkflowController.contentUpdated` **depois** de gravar a nova versão e antes do
    commit (`content/src/java/lumis/content/workflow/WorkflowController.java:55-61`, chamado em
    `content/src/java/lumis/content/table/ContentMetaDataController.java:705-709`);
  - `deleteData`: `WorkflowController.contentDeleted` para cada ContentLocale
    (`content/src/java/lumis/content/workflow/WorkflowController.java:96-111`, chamado em
    `content/src/java/lumis/content/table/ContentMetaDataController.java:843-850`);
  - `lock`: `checkLockPermission` (`content/src/java/lumis/content/core/ContentManager.java:490-511`).
- A leitura de `hasEditPermission` usa o DAO JDBC na mesma conexão, sem cache: um `update` SQL feito
  antes na mesma transação já vale para a checagem.

### Versões publicada e ativa

- `ContentLocale` guarda `activeVersion` e `publishedVersion`; `setPublishedVersion` é público e não
  passa pelo workflow (`content/src/java/lumis/content/core/ContentLocale.java:332-356`).
- `addVersion` cria a versão, numera `n+1` e a torna ativa
  (`content/src/java/lumis/content/core/ContentLocale.java:461-491`).
- Em `addData`, depois do workflow, a versão gravada vira publicada se `contentMayBePublished`
  (estado corrente `isPublished`, ou sem workflow)
  (`content/src/java/lumis/content/table/ContentMetaDataController.java:231-251`,
  `content/src/java/lumis/content/workflow/WorkflowManager.java:998-1018`). Em `updateData`, idem
  (`content/src/java/lumis/content/table/ContentMetaDataController.java:705-725`).
- Com `versioning` na source (campo `VersionActive`), `updateData` grava **nova linha** de negócio e
  nova versão por `addVersionBasedOnAnother`; sem versioning, atualiza a mesma versão
  (`content/src/java/lumis/content/table/ContentTableSource.java:819-902`,
  `content/src/java/lumis/content/table/ContentMetaDataController.java:736-765`). Origem com
  publicada ≠ ativa só existe com versioning.
- `executeAction` zera a publicada quando o estado final é arquivado, ou não publicado com ativa =
  publicada (`content/src/java/lumis/content/workflow/WorkflowManager.java:829-833`) — mais um motivo
  para não usar transições na migração.
- `addVersionBasedOnAnother` copia da base: nome, introdução, SEO, publicações e **associações**
  (`content/src/java/lumis/content/core/ContentLocale.java:577-622`, associações em
  `content/src/java/lumis/content/core/ContentLocale.java:613-619`).

### Categorias e associações

- Ambas são `lum_ContentAssociation` **por versão**: `contentVersion`, `associatedContent` e `type`,
  `standard` ou `categorizationTerm` (`content/src/java/lumis/content/core/ContentAssociation.java:40-56`),
  criadas por `contentVersion.addContentAssociation()` e removidas por `delete()`
  (`content/src/java/lumis/content/core/ContentVersion.java:311-322`).
- **Não passam pelo addData**: o campo `categorization` não gera insert nem update
  (`content/src/java/lumis/content/source/field/CategorizationSourceField.java:90-104`); quem grava é o
  process action handler da tela, que remove as do tipo ausentes e adiciona as novas
  (`content/src/java/lumis/content/control/autolayout/AssociationProcessActionHandler.java:155-175`,
  tipo `categorizationTerm` em
  `content/src/java/lumis/content/control/autolayout/AutoLayoutCategorizationAssociationControl.java:101`;
  associações standard em `content/src/java/lumis/content/table/ContentAssociationProcessActionHandler.java:96-118`).
- A leitura dos termos de uma versão filtra `TYPE_CATEGORIZATION_TERM`
  (`content/src/java/lumis/content/source/field/type/CategorizationDataType.java:606-620`).
- Termos de `lumis.service.categorization` são conteúdos (`lum_CategorizationTerm`) com termo pai
  (`parentTermContentId`); `CategorizationUtil.getTermPathContentIds` sobe a hierarquia pela versão
  publicada de cada termo e `getTermPathNames` devolve os nomes, raiz primeiro
  (`content/src/java/lumis/service/categorization/CategorizationUtil.java:74-134`,
  `content/src/java/lumis/service/categorization/CategorizationUtil.java:148-157`).
- O repositório de categorias da instância vem da dependência `categorization_repository`
  (`content/src/java/lumis/content/source/field/CategorizationSourceField.java:74-78`).

### Tags

- `lum_ContentLocaleTag` por ContentLocale (serializada em
  `content/src/java/lumis/content/service/serialization/ContentServiceSerializationManager.java:646-648`).
  `ContentLocale.addTag(nome)` busca o repositório da dependência `tag_repository`, procura a tag pelo
  nome normalizado e a cria se não existir
  (`content/src/java/lumis/content/core/ContentLocale.java:1004-1047`,
  `content/src/java/lumis/content/core/ContentLocale.java:1060-1097`).
- Pelo addData, o parâmetro `tags` limpa e regrava todas as tags
  (`content/src/java/lumis/content/table/ContentMetaDataController.java:550-583`).

### Comentários editoriais

- `lum_ContentComment` por ContentLocale: `createdBy`, `createdDateTime`, `commentText`
  (`content/src/java/lumis/content/core/ContentComment.java:37-43`).
- **Cada `addData`/`updateData` com o parâmetro `comments` adiciona um comentário novo**, com autor e
  data da sessão (`content/src/java/lumis/content/table/ContentMetaDataController.java:524-530`,
  `content/src/java/lumis/content/core/ContentLocale.java:451-459`). O `updateData` versionado lê o
  parâmetro só do `data` recebido, não da linha original
  (`content/src/java/lumis/content/table/ContentTableSource.java:883-886`).

### Autoria e datas

- `ContentLocale.createdBy`/`createdDateTime` são preenchidos com usuário e hora correntes em
  `addContentLocale` (`content/src/java/lumis/content/core/Content.java:198-209`).
- `ContentVersion.lastModifiedBy`/`lastModifiedDateTime` são sobrescritos com a sessão em toda
  gravação (`content/src/java/lumis/content/table/ContentMetaDataController.java:446-447`).
- Todos têm setter público (`content/src/java/lumis/content/core/ContentLocale.java:263-298`,
  `content/src/java/lumis/content/core/ContentVersion.java:229-262`).

### Idioma

- O idioma do ContentLocale vem do parâmetro `language` (ou `language.languageId`), senão do locale da
  sessão (`content/src/java/lumis/content/table/ContentMetaDataController.java:313-327`). Um segundo
  idioma do mesmo conteúdo usa o parâmetro `contentId`
  (`content/src/java/lumis/content/table/ContentMetaDataController.java:337-355`).
- Idiomas aceitos no destino: `ChannelConfig.getAcceptableLocales()` do canal da instância, que herda do
  canal pai e cai nos idiomas globais (`portal/src/java/lumis/portal/channel/ChannelConfig.java:1110-1135`).

## Algoritmo de migração

Ordem de gravação de um ContentLocale de origem, numa transação do item (PAT001), como o usuário que
executa a migração. É a ordem que `ContentMetadataSpikeTest.cloneContentLocale` prova.

1. **Pré-condições** (antes de gravar, no precheck — PAT002): idioma em
   `getAcceptableLocales()` do canal do destino, senão `LOCALE_NOT_ACCEPTED`; o usuário tem papel no
   estado inicial do workflow do destino (regra de
   `content/src/java/lumis/content/workflow/WorkflowManager.java:675-679`).
2. **addData da publicada** (ou da ativa, se nunca publicada), com campos de negócio já resolvidos pelos
   handlers (PAT002) e os metadados por parâmetro da [tabela](#regra-por-metadatasourcefield). Sem
   `comments`, sem os parâmetros de transição do `WorkflowController`. O ContentLocale nasce no estado
   inicial, atribuído ao usuário (`content/src/java/lumis/content/workflow/WorkflowManager.java:682-689`).
   Segundo idioma do mesmo conteúdo: passe `contentId` = `destContentId` do mapa.
3. **updateData da ativa**, se diferente da publicada, ainda no estado inicial: passa a permissão de
   edição e cria a versão ativa (versioning). O `updateData` copia as associações da publicada para a
   ativa (`content/src/java/lumis/content/core/ContentLocale.java:613-619`).
4. **Associações e categorias por versão**: para cada versão gravada, reconcilie as
   `ContentAssociation` com as da versão de origem correspondente — apague as que a origem não tem e
   adicione as que faltam, tipo a tipo. Termos via handler `categorization_repository`; standard pela
   regra de [Mapeamento e reuso](#mapeamento-e-reuso).
5. **Tags** no ContentLocale por `addTag(nome)` / `removeTag`, no repositório de tags do destino.
6. **setPublishedVersion** com a versão do passo 2, ou `null` se a origem não tem publicada — sempre
   explícito, porque o estado inicial pode ser publicado e o passo 2 pode ter publicado.
7. **Estado e assignedTo**: `flush` e o `update` SQL em `lum_WorkflowMetaData` com o `stateId` da
   origem e o assignedTo pela regra de [Mapeamento e reuso](#mapeamento-e-reuso). Exatamente uma linha
   deve ser alterada; zero é erro do item.
8. **Autoria por último**: `createdBy`/`createdDateTime` do ContentLocale, `lastModifiedBy`/
   `lastModifiedDateTime` de cada versão gravada e comentários editoriais (`addComment` + setters de
   texto, autor e data), porque os passos 2 e 3 os sobrescrevem com a sessão.
9. Linha de MigItemMap e commit na mesma transação (PAT001).

Nada aqui chama `executeAction`: nenhuma transição, action handler, evento de ação, activity de
workflow nem auto-arquivamento é disparado pela migração. O `autoArchivePublishedContent` chamado pelo
add/update (`content/src/java/lumis/content/table/ContentMetaDataController.java:234-236`) roda no estado
inicial, que no workflow padrão não é publicado, e não arquiva
(`content/src/java/lumis/content/workflow/WorkflowManager.java:1049-1051`).

### Regra por MetaDataSourceField

Tipos de `content/src/java/lumis/content/source/field/MetaDataSourceField.java:23-129`, lidos dos
atributos `is*` (`content/src/java/lumis/content/source/field/MetaDataSourceField.java:208-288`); ids
dos campos gerados em `content/src/java/lumis/content/service/ContentService.java:515-522` e
`content/src/java/lumis/content/service/ContentService.java:530-677`.

| Tipo | Campo | Regra na migração |
|---|---|---|
| WorkflowState | `workflowState` | SQL em `lum_WorkflowMetaData` no passo 7; nunca transição. |
| WorkflowAssignedTo | `workflowAssignedTo` | SQL no passo 7; principal sem papel no destino vira `null` com aviso. |
| ServiceInstanceId | `serviceInstanceId` | Não copia: a source grava a instância de destino. |
| PublishToServiceInstances | `publishToServiceInstances` | Copia por parâmetro; o id da instância de origem vira o do destino, os demais ficam. |
| PublishToPrincipals | `publishToPrincipals` | Copia por parâmetro (principais são do portal). |
| PublishStartDate | `publishStartDate` | Copia por parâmetro, inclusive `null`. |
| PublishEndDate | `publishEndDate` | Copia por parâmetro, inclusive `null`. |
| Highlight | `highlight` | Copia por parâmetro. |
| HighlightEndDate | `highlightEndDate` | Copia por parâmetro, inclusive `null`. |
| Comments | `comments` | Nunca por parâmetro; `addComment` no passo 8, sem duplicata (texto, autor e data). |
| Language | `language` | Locale do ContentLocale por parâmetro; precisa estar nos aceitos do canal. |
| LastModifiedDateTime | `lastModifiedDate` | Setter no passo 8, por versão, com `lastModifiedBy`. |
| CreatedDateTime | `createdDate` | Setter no passo 8, com `createdBy` do ContentLocale. |
| VersionContentId | `versionContentId` | Não copia: gerado pelo destino. |
| VersionNumber | `versionNumber` | Não copia: o destino numera a partir de 1; só publicada e ativa migram. |
| VersionActive | `versionActive` | Não copia; define se o passo 3 cria versão. |
| Association | `association` | `ContentAssociation` standard por versão, passo 4. |
| ContentId | `contentId` | Não copia; só para o segundo idioma (`destContentId` do mapa). |
| Lock | `lumLockType` | Não copia; lock de outro usuário no destino falha o item. |
| Tag | `tags` | `addTag`/`removeTag` por nome, passo 5. |
| PublishToSocialNetwork | `publishToSocialNetwork` | Não copia (mensagens são integração); aviso se a origem tem. |
| Categorization | `categorization` | `ContentAssociation` `categorizationTerm` por versão, passo 4, termo pelo caminho de nomes. |
| None | — | Campo comum de negócio; regra do handler do dataType (PAT002). |
| PageSimpleTitle | `pageSimpleTitle` | Copia por parâmetro. |
| PageTitlePattern | `pageTitlePattern` | Copia por parâmetro. |
| CanonicalUrl | `canonicalUrl` | Copia por parâmetro; ausente herdaria da base. |
| UrlTitle | `urlTitle` | Copia por parâmetro. |
| UrlPath | `urlPath` | Copia por parâmetro. |
| SeoDescription | `seoDescription` | Copia por parâmetro. |
| SocialTitle | `socialTitle` | Copia por parâmetro. |
| SocialDescription | `socialDescription` | Copia por parâmetro. |
| SocialImage | `socialImage` | Id de mídia: `resolve` do handler `media_repository` (PAT002). |
| Slug | `slug` | Copia por parâmetro; o destino aplica a regra nativa de slug (`content/src/java/lumis/content/core/ContentLocale.java:817-824`). |
| PrimaryContent | `primaryContent` | Copia por parâmetro. |
| RobotsConfiguration | `robotsConfiguration` | Copia por parâmetro (ausente mantém o da base; campo ausente na source grava `null`). |

"Por parâmetro" quer dizer: sempre presente no mapa do `addData`/`updateData`, mesmo com `null`;
ausente, o controller usa o valor da versão base
(`content/src/java/lumis/content/table/ContentMetaDataController.java:644-662`).

## Mapeamento e reuso

- **Linha do mapa**: uma por ContentLocale de origem (PAT001); `destItemId` = item da versão **ativa**
  do destino, renovado a cada gravação. A publicada é achada pelo ContentLocale
  (`destContentLocaleId`), não precisa de coluna.
- **assignedTo**: mantido se o principal tem algum papel na instância de destino (ele ou um grupo dele,
  `lum_WorkflowPermission` + `IPrincipalManager.getPrincipals`); senão `null` e aviso na linha do mapa.
  Não se exige permissão no estado: a origem pode ter mudado papéis depois da atribuição, e a migração
  copia o estado como está.
- **Estado**: o `stateId` da origem é copiado literalmente (mesmo workflow, por PAT002).
- **Categorias**: o handler `categorization_repository` resolve o termo pelo caminho de nomes
  (`getTermPathNames`) no repositório do destino, clonando os termos que faltam, pai antes do filho,
  com linha `TERM` no mapa (PAT001/PAT002). Com o mesmo repositório na origem e no destino, a
  associação aponta para o mesmo termo, sem clone.
- **Associações standard**: alvo fora da instância de origem fica como está; alvo na instância de
  origem é trocado pelo `destContentId` da linha do mapa; alvo ainda não migrado fica pendente (ver
  [Propostas de contrato](#propostas-de-contrato)).
- **Tags**: por nome, no repositório de tags do destino; `addTag` cria a tag se não existir.
- **Comentários**: chave natural (texto, `createdBy`, `createdDateTime`); comentário igual já presente
  no destino não é regravado.

## Modo atualização

Destino existente pelo mapa (PAT001: busca por `destContentLocaleId`):

1. **Volta ao estado inicial** por SQL, com `assignedTo` = usuário corrente, como na criação nativa
   (`content/src/java/lumis/content/workflow/WorkflowManager.java:684-685`), na transação do item. Sem
   isso, um usuário não admin cujo papel só alcança o estado inicial recebe `AccessDeniedException` no
   `updateData` (`content/src/java/lumis/content/workflow/WorkflowController.java:57-58`).
2. `updateData` da publicada e, se diferente, da ativa (com versioning, cada um cria uma versão;
   sem versioning, um único `updateData` da ativa). Reconcilia associações, categorias e tags por
   versão.
3. `setPublishedVersion`, estado e assignedTo da origem, autoria e comentários novos — passos 6 a 8 da
   criação.

Excluir/desfazer de um conteúdo copiado: volta ao estado inicial e `deleteDataByItemIds` do item ativo,
na mesma transação; `contentDeleted` checa a permissão no estado já reposto
(`content/src/java/lumis/content/workflow/WorkflowController.java:96-111`) e o `@PreRemove` apaga o
metadado de workflow.

Lock: `updateData` e `deleteDataByItemIds` só seguem se o ContentLocale está livre ou bloqueado pelo
próprio usuário (`content/src/java/lumis/content/table/ContentTableSource.java:693-737`,
`content/src/java/lumis/content/table/ContentTableSource.java:980-1013`).

## Falhas e bloqueios

- Usuário sem papel no estado inicial do destino: `addData` falha em
  `content/src/java/lumis/content/workflow/WorkflowManager.java:677-679`. É do usuário, não do item:
  detectar no precheck e bloquear a execução (ver Propostas).
- Idioma fora dos aceitos do canal de destino: `LOCALE_NOT_ACCEPTED` (PAT001), no precheck.
- `update` de estado que altera zero linhas (destino sem workflow): falha do item; PAT002 já impede
  workflows diferentes na criação.
- Conteúdo do destino bloqueado por outro usuário: `ContentLockedException`; falha do item, sem forçar
  `LockStrategy.IGNORE`.
- `AccessDeniedException` no `updateData`/`deleteData` depois de voltar ao estado inicial indica papel
  revogado durante a execução: falha do item (GENERIC) com a mensagem do portal.
- Termo de categoria ou alvo de associação inexistente na origem (apagado): aviso e associação omitida.

## Riscos

- O `update` SQL contorna o manager: não há `WorkflowActionExecutedEvent`, action handlers
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:820-824`) nem activity de transição no
  destino. É o comportamento desejado, mas extensões de projeto que dependem desses eventos não veem a
  migração.
- A indexação e as publicações calculadas são agendadas pelo add/update antes do SQL; o índice lê o
  banco no commit, mas `ContentPublication.updateCalculatedFields` só roda em `executeAction`
  (`content/src/java/lumis/content/workflow/WorkflowManager.java:851-853`). A busca e a listagem do
  destino precisam ser conferidas depois do clone; este documento não registra essa conferência.
- Com `maxVersions` na source, o excesso de versões marca o ContentLocale para limpeza
  (`content/src/java/lumis/content/core/ContentLocale.java:500-525`); o modo atualização cria duas
  versões por execução quando publicada ≠ ativa.
- O usuário que executa a migração aparece como autor da linha de negócio e do lock automático até o
  passo 8; uma falha entre o passo 2 e o commit reverte tudo (transação única), mas um índice externo
  pode ter recebido evento de render.
- Slug e urlPath copiados podem colidir no destino; a regra nativa decide e o valor final pode
  diferir — registrar aviso quando diferir.

## Propostas de contrato

- **PAT002 — associações standard pendentes**: hoje `resolve(campo ou associação, valor)` clona sob
  demanda dependências de repositório, mas o alvo de uma associação standard é outro conteúdo da
  própria migração, que pode ainda não ter sido gravado. Proposta: o engine adia essas associações e
  as reconcilia numa passada final da execução, pelas linhas de MigItemMap; alvo não migrado ao fim
  vira aviso.
- **PAT002 — precheck de usuário**: incluir no precheck da execução (não do item) a checagem de papel do
  usuário no estado inicial do workflow do destino, porque sem ela todos os itens falham.
- **PAT001 — errorType**: avaliar um `WORKFLOW_START_STATE_DENIED` para o caso acima, em vez de
  `GENERIC`, para a administração orientar a concessão do papel.

## Evidências

- Spike: `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/ContentMetadataSpikeTest.java`,
  fixture `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/ContentMetadataFixture.java`,
  sobre duas instâncias de `lumis.service.news` (versioning e workflow,
  `contentservices/src/java/lumis/service/news/douidefinition.xml:17-35`) com o workflow padrão.
- `cloneKeepsStateAssignedToAndPublishedActivePairWithoutTransitions`: origem com a versão 1 aprovada e a
  2 em revisão atribuída ao editor; o clone, executado pelo usuário autor, fica em `reviewing`,
  atribuído ao editor, com duas versões e publicada ≠ ativa iguais às da origem, autoria e um único
  comentário iguais. O teste prova que, pelas transições que o autor pode executar a partir do estado
  inicial, `reviewing` é inalcançável (`approve` exige editor,
  `content/src/java/lumis/workflow/standard/workflowdefinition.xml:31-38`).
- `nonAdminWithStartStateRoleUpdatesAndDeletesContentInRestrictedClonedState`: clone em `approved`
  atribuído ao editor (autor sem papel no estado,
  `content/src/java/lumis/workflow/standard/workflowdefinition.xml:66-79`); o autor não tem
  `hasEditPermission` e recebe `AccessDeniedException` no `updateData` e no `deleteData` nativos; com
  volta ao estado inicial na mesma transação, atualiza (estado e assignedTo reaplicados) e apaga.
- Execução: `bash test/run-tests.sh ContentMetadataSpikeTest` (validação VAL-SPIKE-CONTENTMETA).

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
- Os passos de metadados ficam em `ItemProcessor.copyLocaleMetadata(scope, originLocale, destinationVersions, originWorkflow, mappedAssignedTo)`,
  na ordem `copyState`, `setPublishedVersion`, tags e `AuthorshipCloner.copy(originLocale, destinationLocale, destinationPublished, destinationActive)`;
  depois vem `saveItemMap(scope, item, existingMap, originActiveVersion, destinationActiveVersion)`.
  As associações são copiadas por `ContentCopier.copyStandardAssociations(scope, originVersion, destinationVersion)`
  e `copyCategorizationAssociations(scope, dependencies, originVersion, destinationVersion)`, e o termo
  de categorização é lido como `TermEntry(locale, version, name, description, parentTermContentId)`,
  com o `Content` tirado de `locale.getContent()`.
