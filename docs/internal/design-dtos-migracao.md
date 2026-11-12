# DTOs e limite de parâmetros no serviço de migração

Estudo de design dos DTOs do serviço `contentmigration` e do limite de parâmetros por declaração. O documento lista toda declaração longa de `src` no código anterior à refatoração, define os dois DTOs e diz, para cada método, o que muda e em qual etapa da implementação (1, 2 ou 3, descritas em “Ordem de implementação”). A base do levantamento é o código anterior à refatoração, que não faz parte do histórico publicado: os números de linha do inventário valem para essa versão, e “hoje”, no texto, é o código dessa base.

**Estado atual:** as três etapas foram implementadas. As assinaturas de “Destino de cada método” são as do código atual, e nenhuma declaração de `src` passa de 5 parâmetros: `test/check_style.py` aplica esse limite por padrão aos arquivos de `src` (`SRC_MAX_PARAMS`), e `bash test/run-static-checks.sh` o executa.

No texto, `R` é o pacote `br.com.lumis.commons.contentmigrator.service.contentmigration`, em `src/br/com/lumis/commons/contentmigrator/service/contentmigration`.

**Limite proposto:** no máximo 5 parâmetros por método ou construtor de `src`. Declaração com 6 ou mais muda. Declaração com exatamente 5 fica como está, a menos que um DTO a reduza sem custo extra. A exceção `// style:many-params`, com o motivo na linha anterior, continua disponível, mas este design não precisa dela.

## Inventário

**Como foi levantado:** um script léxico removeu comentários e literais de todos os `.java` de `src` e contou as vírgulas de primeiro nível de cada declaração. O resultado foi conferido com `javap -p` sobre `target/classes`. Os dois métodos batem: são **60 declarações com 5 ou mais parâmetros**, sendo **30 com 6 ou mais** e **30 com exatamente 5**. O `javap` mostra três `updateItem` a mais, que são métodos-ponte gerados pela interface genérica `RepositoryCloner.Adapter`, não declarações do código-fonte.

Causas usadas na tabela:

- **G** (grupo repetido): sessão, transação, conexão, `serviceId`, instâncias de origem e destino, sources, modo de atualização, warnings ou `fileCloner`, os mesmos valores passados de método em método.
- **P** (objeto da plataforma solto ou derivável): um valor que outro parâmetro já fornece, como a conexão (vem da transação), as versões de origem (vêm do `ContentLocale`) ou o `Content` (vem do `ContentLocale`).
- **F** (flag): um booleano ou String de modo que escolhe o caminho, ou uma sobrecarga que só fixa um valor padrão.
- **N** (necessidade real): valores independentes, sem grupo que os reúna.

| Arquivo (em `R`) | Linha | Declaração | Nº | Causa | Etapa |
| --- | --- | --- | --- | --- | --- |
| `api/MigrationApi.java` | 218 | `validateCreationPairAndPermissions` | 6 | G, N: três verificações independentes juntas (par duplicado, permissão, regras) | 3 |
| `api/MigrationApi.java` | 251 | `insertStoppedMigration` | 7 | G: campos de `MigrationRow` passados soltos | 3 |
| `api/MigrationApi.java` | 277 | `saveRepositoryMappings` | 5 | G: conexão, sessão, transação e par de instâncias | mantém |
| `api/MigrationApi.java` | 503 | `deleteUnusedRepositoryMaps` | 5 | G | mantém |
| `api/MigrationApi.java` | 637 | `resolveBlockedMigrationWidgetTarget` | 7 | G: pedido de mapeamento de widget solto, mais conexão, sessão e transação | 3 |
| `api/MigrationApi.java` | 687 | `matchesBlockedMigrationWidgetTarget` | 5 | G: campos da migração bloqueada e do pedido soltos | 3 (absorvido) |
| `api/MigrationApi.java` | 721 | `verifyBlockedMigrationInstancePermissions` | 5 | N | mantém |
| `copy/AuthorshipCloner.java` | 25 | `copy` | 6 | P: versões de origem deriváveis de `originLocale` | 1 |
| `copy/ContentCopier.java` | 64 | `validateLocale` | 5 | N | mantém |
| `copy/ContentCopier.java` | 114 | `createDependencyContext` | 9 | G | 2 (removido) |
| `copy/ContentCopier.java` | 146 | `buildData` | 16 | G: escopo inteiro, mais versão, linha e ids de destino | 1 |
| `copy/ContentCopier.java` | 220 | `putIdentityParameters` | 5 | G | 1 (absorvido) |
| `copy/ContentCopier.java` | 256 | `buildDependencyContexts` | 9 | G | 1 (removido) |
| `copy/ContentCopier.java` | 300 | `tryHandleSpecialMetadataField` | 11 | G, P: valor e publicação deriváveis da versão | 1 |
| `copy/ContentCopier.java` | 381 | `resolveFieldValue` | 11 | G | 1 |
| `copy/ContentCopier.java` | 415 | `resolveParentContentId` | 5 | G | 1 (absorvido) |
| `copy/ContentCopier.java` | 487 | `applyAfterWrite` | 11 | G | 2 |
| `copy/ContentCopier.java` | 514 | `copyStandardAssociations` | 6 | G | 2 |
| `copy/ContentCopier.java` | 552 | `resolveStandardAssociationTarget` | 5 | G | 2 (absorvido) |
| `copy/ContentCopier.java` | 584 | `copyCategorizationAssociations` | 10 | G | 2 |
| `copy/ItemProcessor.java` | 73 | `ItemProcessor` | 5 | F: sobrecarga que fixa `ADD_ONLY`, sem chamador | 1 (removido) |
| `copy/ItemProcessor.java` | 83 | `ItemProcessor` | 6 | G: campos da migração soltos | 1 |
| `copy/ItemProcessor.java` | 383 | `copyVersionsAndMetadata` | 15 | G, P: escopo e estado do item soltos; versões deriváveis do idioma | 1 |
| `copy/ItemProcessor.java` | 462 | `copySingleVersion` | 11 | G | 1 |
| `copy/ItemProcessor.java` | 555 | `saveItemMap` | 7 | G, P: idioma e item de destino deriváveis da versão ativa | 1 |
| `copy/MetadataPolicy.java` | 89 | `resolveUniqueSlug` | 6 | G | 1 |
| `copy/MetadataPolicy.java` | 124 | `isSlugInUse` | 5 | G | 1 (absorvido) |
| `copy/WorkflowCloner.java` | 95 | `resolveAssignedTo` | 5 | G | 1 (absorvido) |
| `dependency/categorization/CategorizationDependencyHandler.java` | 259 | `TermEntry` | 6 | P: o `Content` vem de `locale.getContent()` | 2 |
| `dependency/categorization/CategorizationDependencyHandler.java` | 482 | `updateItem` | 5 | F: `fileChanged`, contrato de `Adapter` | mantém |
| `dependency/DependencyContext.java` | 44 | `DependencyContext` | 7 | G: sobrecarga usada só pelos testes, por reflexão | 2 (removido) |
| `dependency/DependencyContext.java` | 70 | `DependencyContext` | 10 | G | 2 |
| `dependency/document/DocumentDependencyHandler.java` | 679 | `DocumentEntry` | 5 | N | mantém |
| `dependency/document/DocumentDependencyHandler.java` | 847 | `updateItem` | 5 | F: contrato de `Adapter` | mantém |
| `dependency/document/DocumentDependencyHandler.java` | 853 | `write` | 8 | F: `add` (derivável de `destination == null`) e `writeFile` | 2 |
| `dependency/media/MediaDependencyHandler.java` | 605 | `MediaEntry` | 5 | N | mantém |
| `dependency/media/MediaDependencyHandler.java` | 771 | `updateItem` | 5 | F: contrato de `Adapter` | mantém |
| `dependency/media/MediaDependencyHandler.java` | 776 | `createOrUpdate` | 8 | F: `add` e `writeFile` | 2 |
| `dependency/media/MediaDependencyHandler.java` | 818 | `buildMediaData` | 6 | F: `writeFile` | 2 |
| `dependency/media/MediaDependencyHandler.java` | 861 | `syncMediaFile` | 6 | F: `add` derivável | 2 |
| `dependency/RepositoryCloner.java` | 74 | `Adapter.updateItem` | 5 | F: `fileChanged` | mantém |
| `dependency/RepositoryCloner.java` | 227 | `saveMap` | 5 | N | mantém |
| `dependency/RepositoryCloner.java` | 335 | `MapRow` | 5 | N: uma linha de 5 colunas | mantém |
| `dependency/widget/WidgetDependencyHandler.java` | 276 | `copyEmbedConfigurationIfSameInterface` | 5 | N | mantém |
| `execution/MigrationTask.java` | 66 | `MigrationTask` | 6 | F: fixa `ADD_ONLY`, sem chamador | 3 (removido) |
| `execution/MigrationTask.java` | 74 | `MigrationTask` | 7 | F: fixa `OPERATION_MIGRATE` | 3 (removido) |
| `execution/MigrationTask.java` | 90 | `MigrationTask` | 8 | G: campos de `MigrationRow` soltos | 3 |
| `execution/RunStatusUpdater.java` | 36 | `markRunning` | 5 | N | mantém |
| `execution/RunStatusUpdater.java` | 125 | `finish` | 5 | N | mantém |
| `execution/RunStatusUpdater.java` | 196 | `updateCounts` | 5 | N | mantém |
| `query/MigrationAdminQueries.java` | 88 | `listInstancesByService` | 5 | F: `destination`; chamado por reflexão em `AdminFormValidationTest` | mantém |
| `query/WidgetMappingQueries.java` | 147 | `checkCanManage` | 5 | G | mantém |
| `security/MigrationPermissions.java` | 46 | `hasServiceInstancePermission` | 5 | N: espelha a ACL da plataforma | mantém |
| `undo/MigrationUndoer.java` | 84 | `isRepositoryPairUsedByAnother` | 5 | G | mantém |
| `undo/MigrationUndoer.java` | 543 | `deleteDestinationContentLocale` | 6 | P: a conexão vem da transação | 3 |
| `undo/MigrationUndoer.java` | 567 | `undoRepositoryItem` | 6 | G | 2 |
| `undo/MigrationUndoer.java` | 615 | `undoExistingRepositoryClone` | 10 | G | 2 |
| `validation/DependencyRules.java` | 100 | `checkRequiredRepositories` | 5 | G, mais o acumulador de violações | mantém |
| `validation/DependencyRules.java` | 118 | `checkSharedRepositories` | 5 | G, mais o acumulador | mantém |
| `validation/DependencyRules.java` | 140 | `checkWorkflowCompatibility` | 5 | G, mais o acumulador | mantém |

`WidgetMappingQueries.checkCanManage` não tem chamador em `src`, nos testes nem nos XML. Ele é candidato a remoção, mas isso ficou fora deste design, porque a declaração já está no limite; no código atual, ele continua declarado e sem chamador.

**Fixtures e testes (só como nota, o limite não se aplica):** 13 declarações em `test/java`. São elas `CategorizationFixture.addTermLocale` (5), `ContentMetadataFixture.addNews` (6) e `updateNews` (6), `DocumentFixture.addDocument` (7), `MediaFixture.addMedia` (6), `WidgetFixture.addData` (5) e `readColumn` (5), `CategorizationMigrationTest.insertTermMap` (5), `SimpleMigrationTest.addNews` (5 e 7), `WorkflowMigrationTest.assertClone` (5), `SpiRulesTest.addPost` (7) e `ContentMetadataSpikeTest.cloneContentLocale` (5). Nenhuma muda neste design; só o helper novo da etapa 2 entra em `ModuleBridge`.

## Design dos DTOs

### O que pode morar em `R.dto`

O mapa de pacotes de [check_structure.py](../../test/check_structure.py) diz que `dto` não depende de nada do módulo, e que `dependency` depende só de `dao`, `dto` e `exception`. Por isso:

- Os dois DTOs ficam em `R.dto` e referenciam só `java.*` e `lumis.*`. Já existe precedente: `FileCopy` guarda um `FileDataType.Data` da plataforma.
- `FileCloner` e `DependencyHandler` ficam em `R.dependency` e **não** entram em nenhum DTO.
- O objeto que monta os contextos dos handlers também não pode ficar em `R.dependency`, porque chama `DependencyRules.repository` (`R.validation`), e a direção `dependency → validation` é proibida. Ele fica em `R.copy`; ver `ItemDependencies` abaixo.

### `MigrationScope` (DTO global, em `R.dto`)

`MigrationScope` reúne o que cada passo de uma transação de item precisa saber. Há um escopo por transação: na cópia, `ItemProcessor.processOnce` o cria logo depois de carregar as instâncias e antes dos prechecks; no desfazer, `MigrationUndoer.undoRepositoryItem` o cria depois do `begin`. Uma nova tentativa (`process` chamando `processOnce` de novo depois de um conflito em `LUM_UN_CMIG_MIGITEMMAP`) cria outro escopo, com warnings vazios, como hoje.

| Campo | Tipo | Pode ser nulo? | Validação em `build()` |
| --- | --- | --- | --- |
| `session` | `SessionConfig` | não | `Objects.requireNonNull(session, "session")` |
| `transaction` | `ITransaction` | não | `requireNonNull` |
| `connection` | `Connection` | não | `requireNonNull`; é a conexão da própria transação, passada por quem a abriu |
| `serviceId` | `String` | não | `requireNonNull` |
| `originServiceInstanceId` | `String` | não | `requireNonNull` |
| `destinationServiceInstanceId` | `String` | não | `requireNonNull` |
| `originSource` | `ContentTableSource` | sim, no desfazer e nos testes | os dois sources são nulos juntos; um só nulo gera `IllegalStateException` |
| `destinationSource` | `ContentTableSource` | sim, junto com `originSource` | idem |
| `updateMode` | `boolean` | não se aplica | padrão `false` (é o caso do desfazer) |
| `warnings` | `List<String>` | nunca | criada pelo próprio escopo, vazia |

- **Imutabilidade:** classe `final`, campos `final`, construtor privado chamado só pelo `Builder`, só getters. A exceção deliberada é a lista de warnings: ela é o acumulador do item. `getWarnings()` devolve a própria lista, compartilhada com os `DependencyContext` do item, e `addWarning(String)` acrescenta sem deduplicar, como fazem hoje os `warnings.add` de `ContentCopier`, `MetadataPolicy`, `WorkflowCloner` e `ItemProcessor`. A `SessionConfig` também é mutável (`setLocale`); o DTO guarda a referência, como os métodos guardam hoje.
- **Builder:** a classe tem 10 campos, então usa `Builder` (acima de 5 campos). O builder é obtido por `MigrationScope.builder()` e tem os métodos `session`, `transaction`, `connection`, `serviceId`, `originServiceInstanceId`, `destinationServiceInstanceId`, `sources(ContentTableSource origin, ContentTableSource destination)` e `updateMode(boolean)`. A conexão é campo próprio, e não derivada da transação no getter, porque `ITransactionJdbc.getConnection()` lança `DaoException` e os métodos de SQL hoje declaram só `SQLException`.
- **Fora do escopo:**
  - `runId`, que só `GlobalLock.confirm` usa, em `ItemProcessor` e `MigrationUndoer`.
  - `MigrationItem` e `UndoItem`, que pertencem ao item e não ao escopo.
  - `fileCloner` e os handlers, que pertencem ao pacote `dependency`.

### `VersionCopy` (DTO do conteúdo, em `R.dto`)

`VersionCopy` descreve uma versão de origem a gravar no destino. São uma ou duas por item (a publicada e a ativa), na ordem de `buildOriginVersionsToCopy`.

| Campo | Tipo | Pode ser nulo? | Validação no construtor |
| --- | --- | --- | --- |
| `originRow` | `ISourceData` | não | `requireNonNull` |
| `originVersion` | `ContentVersion` | não | `requireNonNull` |
| `destinationContentId` | `String` | sim: inclusão de um conteúdo novo (idioma padrão ainda não migrado) | nenhuma |
| `destinationContentLocaleId` | `String` | sim: inclusão (o idioma ainda não existe no destino) | nulo junto com `destinationItemId` |
| `destinationItemId` | `String` | sim, exatamente quando `destinationContentLocaleId` é nulo | `IllegalArgumentException` se só um dos dois for nulo |

- **Construtor:** recebe os 5 campos (está no limite; sem `Builder`, que só vale acima de 5).
- **Invariante:** o par de ids de destino repete o que `copySingleVersion` já garante hoje, com `currentDestinationLocale == null ? null : currentDestinationItemId`.
- **Métodos:** só getters, mais `hasDestinationLocale()`, que diz se a gravação é `updateData` ou `addData`.

### `fileCloner`, handlers e contextos: `ItemDependencies` (em `R.copy`)

`ItemDependencies` é uma classe `final` package-private de `R.copy`. Não é DTO: guarda os contextos já resolvidos de um item.

- `static ItemDependencies create(MigrationScope scope, FileCloner fileCloner)` tira um retrato de `DependencyHandlerRegistry.getHandlers()`, na ordem media, document, widget, categorization, e monta um `DependencyContext` por tipo com `DependencyRules.repository` de origem e de destino.
- Os acessos são `getHandlers()`, `getHandler(String type)` (nulo se o tipo não estiver registrado), `getContext(DependencyHandler handler)`, `findHandler(ISourceField field)` (sai de `ContentCopier`) e `getFileCloner()`.
- **Dono do `fileCloner`:** o `try` com recursos de `processOnce`. Ele passa a abrir antes dos prechecks (a pasta temporária só é criada na primeira cópia) e continua fechando depois do commit ou do rollback. `ItemDependencies` não o fecha.
- **Uma instância por item.** Hoje os contextos são criados uma vez por versão, outra vez para a categorização e outra por precheck. Os valores são os mesmos, porque o item não altera as dependências das instâncias. Os prechecks dos quatro handlers não usam `getFileCloner()`; só `Adapter.copyFile` usa, dentro de `resolve`. Por isso, passar a ter um cloner no precheck não muda nada.

### `DependencyContext` construído a partir do DTO global

**Decisão:** sim. Depois da etapa 2, o único construtor é `DependencyContext(MigrationScope scope, RepositoryPair repositories, FileCloner fileCloner)`, e os de 7 e 10 parâmetros saem.

- `repositories` é o `RepositoryPair` que já existe em `R.dto`, com os ids dos repositórios de origem e de destino e o tipo da dependência.
- **Nulos:**
  - `scope` e `repositories` são validados com `requireNonNull`.
  - Os ids de repositório podem ser nulos quando a instância não tem repositório do tipo; `isSameRepository()` devolve `false`, como hoje.
  - `fileCloner` pode ser nulo no desfazer e nos testes.
- **Handlers:** não mudam. Os getters ficam com os mesmos nomes e a mesma semântica, inclusive os nomes antigos `getDestServiceInstanceId` e `getDestRepositoryInstanceId`, e `getConnection()` mantém `throws Exception`. Os getters são `getSession`, `getTransaction`, `getConnection`, `getServiceId`, `getOriginServiceInstanceId`, `getDestServiceInstanceId`, `getOriginRepositoryInstanceId`, `getDestRepositoryInstanceId`, `isSameRepository`, `isUpdateMode`, `addWarning`, `getWarnings` e `getFileCloner`.
  - `addWarning` continua ignorando texto vazio e deduplicando, agora sobre `scope.getWarnings()`.
  - `getWarnings` continua devolvendo uma vista só de leitura.
  - Só mudam os dois pontos que constroem o contexto: `ItemDependencies.create` e `MigrationUndoer.undoExistingRepositoryClone`.
- **Testes por reflexão:** cinco pontos chamam hoje o construtor de 7 parâmetros com `getConstructor`: `SpiRulesTest.isSameRepository`, `MediaMigrationTest.isCloneReferenced`, `DocumentMigrationTest.isCloneReferenced`, `CategorizationMigrationTest.isCloneReferenced` e `CategorizationMigrationTest.deleteClone`. Na etapa 2:
  - Entra em `ModuleBridge` o helper `newDependencyContext(Map<String, Object> scope, String originRepositoryId, String destinationRepositoryId)`. Ele monta o `MigrationScope` pelo `Builder`, chamando o método de mesmo nome de cada chave, como `newRow` já faz com os setters, depois o `RepositoryPair` e o contexto com `FileCloner` nulo.
  - Os cinco pontos passam a usar esse helper.
  - `SpiRulesTest` hoje constrói o contexto com sessão, transação e `serviceId` nulos. Como o `Builder` valida esses campos, a verificação de `isSameRepository` passa a rodar dentro de `PortalFixture.inTransaction`, com sessão e transação reais e `PortalFixture.NEWS_SERVICE_ID`.

### Direção de pacotes sem ciclo

Referências que o design cria ou mantém:

| De → para | Por quê | Permitido no mapa? |
| --- | --- | --- |
| `copy → dto` | `MigrationScope`, `VersionCopy`, `MigrationRow` em `ItemProcessor` e `ContentCopier` | sim |
| `copy → dependency` | `ItemDependencies` usa `DependencyContext`, `DependencyHandler`, `DependencyHandlerRegistry` e `FileCloner` | sim (já existe) |
| `copy → validation` | `ItemDependencies` chama `DependencyRules.repository` | sim (já existe) |
| `dependency → dto` | `DependencyContext` guarda `MigrationScope` e `RepositoryPair` | sim |
| `undo → dto` | `MigrationScope` e `MigrationRow` em `MigrationUndoer` | sim (já usa `dto`) |
| `execution → dto` | `MigrationRow` em `MigrationTask` | sim (já existe) |
| `api → dto` | `MigrationRow` em `create`; `WidgetMappingRequest` é aninhado em `MigrationApi` | sim (já existe) |

**Arestas que o design evita:** `dto → dependency` (por isso `fileCloner` e os handlers ficam fora dos DTOs) e `dependency → validation` (por isso `ItemDependencies` fica em `copy`).

**Prova:** o mapa de `check_structure.py` é acíclico. Basta a ordem topológica a seguir, em que cada nó depende só de nós à sua esquerda: `dto`, `config`, `security`, `exception`, `dao`, `lock`, `validation`, `dependency`, `copy`, `undo`, `execution`, `query`, `api` e, por último, `dataprovider`, `processaction` e `rest`. O design só usa arestas que já estão no mapa, então o grafo continua sem ciclo. Além disso, `dto` não tem aresta de saída dentro do módulo, e nenhuma aresta nova que entra nele pode fechar um ciclo. Cada etapa confirma isso com `python3 test/check_structure.py`, que acusa `forbidden direction` e `cycle:`.

## Destino de cada método

Assinaturas depois da etapa dona, que são as do código atual. O número entre parênteses é a quantidade de parâmetros.

### Declarações com 6 ou mais parâmetros

| Declaração | Hoje | Depois | Solução | Etapa |
| --- | --- | --- | --- | --- |
| `ItemProcessor` (construtor) | 6 | `ItemProcessor(String runId, SessionConfig session, MigrationRow migration)` (3) | DTO próprio: a `MigrationRow` existente descreve a execução; o construtor copia `serviceId`, instâncias e `updateMode` (nulo vira `ADD_ONLY`) para campos `final` | 1 |
| `ItemProcessor.copyVersionsAndMetadata` | 15 | `copyVersions(scope, dependencies, originVersions, existingDestinationLocale, destinationContentId)` (5), que devolve as versões gravadas em ordem; `copyLocaleMetadata(scope, originLocale, destinationVersions, originWorkflow, mappedAssignedTo)` (5); `saveItemMap` (abaixo) | divisão do método mais os dois DTOs; `copyLocaleMetadata` mantém a ordem `copyState`, `setPublishedVersion`, `applyTags`, `AuthorshipCloner.copy` | 1 |
| `ItemProcessor.copySingleVersion` | 11 | `copySingleVersion(scope, dependencies, version)` (3) | `MigrationScope` e `VersionCopy`. O laço de `copyVersions` lê a linha com `readOriginRow(scope, originVersion)` (a mensagem de erro usa `originVersion.getContentLocale().getId()` e `scope.getOriginSource().getId()`, os mesmos valores de hoje) e monta o `VersionCopy`. `writeVersionData(scope, version, data)` (3) escolhe `updateData` por `version.hasDestinationLocale()` | 1 |
| `ItemProcessor.saveItemMap` | 7 | `saveItemMap(scope, item, existingMap, originActiveVersion, destinationActiveVersion)` (5) | escopo; idioma e item de destino saem de `destinationActiveVersion`, que é a última versão gravada, igual ao `currentDestinationLocale` e ao `destinationItemId` de hoje | 1 |
| `AuthorshipCloner.copy` | 6 | `copy(originLocale, destinationLocale, destinationPublished, destinationActive)` (4) | dado derivável: as versões publicada e ativa de origem são `originLocale.getPublishedVersion()` e `getActiveVersion()`, exatamente como `processOnce` as obtém; a origem não é escrita durante o item | 1 |
| `MetadataPolicy.resolveUniqueSlug` | 6 | `resolveUniqueSlug(scope, requestedSlug, currentDestinationContentLocaleId)` (3) | escopo: conexão, instância de destino, `scope.getOriginSource().getId()` e warnings | 1 |
| `ContentCopier.buildData` | 16 | `buildData(scope, dependencies, version)` (3) | os dois DTOs e `ItemDependencies` | 1 |
| `ContentCopier.buildDependencyContexts` | 9 | removido | `ItemDependencies.create`; na etapa 1, `create` ainda chama o `createDependencyContext` antigo com os getters do escopo e `scope.getWarnings()` | 1 |
| `ContentCopier.tryHandleSpecialMetadataField` | 11 | `tryHandleSpecialMetadataField(scope, dependencies, version, data, metadata)` (5) | valor lido de `version.getOriginRow()`; publicação de `firstPublication(version.getOriginVersion())`, só nos ramos `PublishTo*`; ordem dos testes de tipo mantida | 1 |
| `ContentCopier.resolveFieldValue` | 11 | `resolveFieldValue(scope, dependencies, field, value)` (4) | escopo; contexto e `fileCloner` vêm de `dependencies` | 1 |
| `ContentCopier.applyAfterWrite` | 11 | `applyAfterWrite(scope, dependencies, originVersion, destinationVersion)` (4) | escopo e `ItemDependencies` | 2 |
| `ContentCopier.copyStandardAssociations` | 6 | `copyStandardAssociations(scope, originVersion, destinationVersion)` (3) | escopo | 2 |
| `ContentCopier.copyCategorizationAssociations` | 10 | `copyCategorizationAssociations(scope, dependencies, originVersion, destinationVersion)` (4) | handler e contexto de categorização vêm de `dependencies.getHandler(...)`; com o tipo ausente, sai como hoje | 2 |
| `ContentCopier.createDependencyContext` | 9 | removido | a criação passa para `ItemDependencies.create`, com o construtor novo de `DependencyContext` | 2 |
| `DependencyContext` (7) | 7 | removido | só os testes usavam; passam ao helper de `ModuleBridge` | 2 |
| `DependencyContext` (10) | 10 | `DependencyContext(MigrationScope scope, RepositoryPair repositories, FileCloner fileCloner)` (3) | DTO global | 2 |
| `MediaDependencyHandler.createOrUpdate` | 8 | `buildMediaData(origin, destination, parentId, fileToWrite, titleToWrite)` (5) mais `save(origin, destination, data, destinationContentId)` (4) | remoção das flags. `add` é `destination == null`. `writeFile` passa a ser `fileToWrite != null`: `createItem` passa o arquivo, `updateItem` passa `fileChanged ? file : null`, as pastas passam `null`. `save` troca o locale da sessão, grava e sincroniza o arquivo | 2 |
| `MediaDependencyHandler.buildMediaData` | 6 | 5, como acima | remoção de `writeFile` | 2 |
| `MediaDependencyHandler.syncMediaFile` | 6 | `syncMediaFile(origin, destination, target, itemId, isFileWritten)` (5) | `add` derivado de `destination == null` | 2 |
| `DocumentDependencyHandler.write` | 8 | `buildDocumentData(origin, destination, parentId, fileToWrite, titleToWrite)` (5) mais `save(origin, destination, data, destinationContentId)` (4) | as mesmas flags de mídia. O `missingFile(origin)` sai de `write` e vai para `createItem` (arquivo nulo) e `updateItem` (`fileChanged` com arquivo nulo), antes de qualquer gravação, com a mesma exceção | 2 |
| `CategorizationDependencyHandler.TermEntry` | 6 | `TermEntry(locale, version, name, description, parentTermContentId)` (5) | dado derivável: `content` é `locale.getContent()`, porque `TermAdapter.load` tira o idioma do próprio `content` (`getContentLocale` ou o primeiro de `getContentLocales()`) | 2 |
| `MigrationUndoer.undoRepositoryItem` | 6 | `undoRepositoryItem(SessionConfig session, String runId, MigrationRow migration, UndoItem item)` (4) | DTO próprio (`MigrationRow`); cria o `MigrationScope` do desfazer depois do `begin` | 2 |
| `MigrationUndoer.undoExistingRepositoryClone` | 10 | `undoExistingRepositoryClone(scope, item, handler)` (3) | escopo; o contexto é `new DependencyContext(scope, item.getRepositoryPair(), null)` e o idioma vem de `item.getMap()` | 2 |
| `MigrationTask` (6) | 6 | removido | sobrecarga sem chamador | 3 |
| `MigrationTask` (7) | 7 | removido | sobrecarga que fixava `OPERATION_MIGRATE` | 3 |
| `MigrationTask` (8) | 8 | `MigrationTask(MigrationRow migration, String runId, String userId)` (3) | DTO próprio. `start` e `undo` releem a linha depois de `markRunning` e `markUndoRunning`, com o `updateMode` e a `operation` gravados, e criam a tarefa dentro de `runInTransaction`, antes do commit. O construtor não valida nem lança exceção | 3 |
| `MigrationApi.validateCreationPairAndPermissions` | 6 | `rejectDuplicatePair(connection, originInstanceId, destinationInstanceId)` (3), `requireCreatePermission(author, originInstanceId, destinationInstanceId, transaction)` (4) e `checkCreationRules(session, originInstanceId, destinationInstanceId, transaction)` (4) | divisão do método, chamada na mesma ordem: par, permissão, regras | 3 |
| `MigrationApi.insertStoppedMigration` | 7 | `insertStoppedMigration(Connection connection, MigrationRow migration)` (2) | DTO próprio: `create` preenche nome, `serviceId`, instâncias, `updateMode` e autor; o método completa id, operação, status e contadores | 3 |
| `MigrationApi.resolveBlockedMigrationWidgetTarget` | 7 | `resolveBlockedMigrationWidgetTarget(connection, session, transaction, request)` (4) | DTO próprio: classe aninhada privada `WidgetMappingRequest` (`migrationId`, `originWidgetContentId`, `destinationRepositoryId`, `requestingUserId`), montada em `saveWidgetMapping` antes da transação, como os valores de hoje | 3 |
| `MigrationUndoer.deleteDestinationContentLocale` | 6 | `deleteDestinationContentLocale(session, transaction, destinationServiceInstanceId, map, locale)` (5) | dado derivável: conexão de `((ITransactionJdbc) transaction).getConnection()`, como a classe já faz | 3 |

Os dois DTOs e `ItemDependencies` são criados na etapa 1.

### Declarações com 5 parâmetros

- **Absorvidas sem custo** (mudam junto com o método dono):

  | Declaração | Depois | Etapa |
  | --- | --- | --- |
  | `ItemProcessor` (construtor de 5) | removido, sem chamador | 1 |
  | `ContentCopier.putIdentityParameters` | `(scope, version, data)` (3) | 1 |
  | `ContentCopier.resolveParentContentId` | `(scope, value)` (2) | 1 |
  | `MetadataPolicy.isSlugInUse` | `(scope, slug, currentContentLocaleId)` (3) | 1 |
  | `WorkflowCloner.resolveAssignedTo` | `(scope, origin)` (2); o único chamador é `ItemProcessor` | 1 |
  | `ContentCopier.resolveStandardAssociationTarget` | `(scope, associated)` (2) | 2 |
  | `MigrationApi.matchesBlockedMigrationWidgetTarget` | `(migration, linkedRepository, request)` (3); os dois campos bloqueados vêm da `MigrationRow` | 3 |

  `ContentCopier.prepareClonedValue`, que tem 4 parâmetros hoje, também passa a `(dependencies, field, value)` (3) na etapa 1.
- **Mantidas no limite**, sem dono. Todas têm causa N, ou G sem escopo de item disponível no ponto de chamada:
  - `MigrationApi`: `saveRepositoryMappings`, `deleteUnusedRepositoryMaps` e `verifyBlockedMigrationInstancePermissions`.
  - `ContentCopier.validateLocale`.
  - Contrato de `RepositoryCloner.Adapter`: `updateItem` (a interface e as três implementações).
  - `RepositoryCloner.saveMap` e os construtores `MapRow`, `MediaEntry` e `DocumentEntry`.
  - `WidgetDependencyHandler.copyEmbedConfigurationIfSameInterface`.
  - `RunStatusUpdater`: `markRunning`, `finish` e `updateCounts`.
  - `MigrationAdminQueries.listInstancesByService`, que `AdminFormValidationTest` chama por reflexão com 5 argumentos.
  - `WidgetMappingQueries.checkCanManage` e `MigrationPermissions.hasServiceInstancePermission`.
  - `MigrationUndoer.isRepositoryPairUsedByAnother`.
  - Os três `check*` de `DependencyRules`. Eles rodam na criação da migração, sem transação de item, e por isso não usam `MigrationScope`.

### Fluxo de `processOnce` depois da etapa 1

A sequência de escrita é a de hoje. Só mudam de lugar a criação das sources e a abertura do `FileCloner`, que são leituras e não escrevem nada.

```java
transaction.begin();
// mapa, SKIPPED, idioma e versões de origem, instâncias, validateLocale e session.setLocale: como hoje
MigrationScope scope = MigrationScope.builder()
		.session(session)
		.transaction(transaction)
		.connection(connection)
		.serviceId(serviceId)
		.originServiceInstanceId(originServiceInstanceId)
		.destinationServiceInstanceId(destinationServiceInstanceId)
		.sources(originSource, destinationSource)
		.updateMode(isUpdateMode())
		.build();
try (FileCloner fileCloner = new FileCloner())
{
	ItemDependencies dependencies = ItemDependencies.create(scope, fileCloner);
	runDependencyPrechecks(dependencies, item);
	// readWorkflowMetaData, resolveAssignedTo(scope, originWorkflow), returnToStartState: como hoje
	List<ContentVersion> destinationVersions = copyVersions(scope, dependencies, originVersions, existingDestinationLocale, destinationContentId);
	copyLocaleMetadata(scope, originLocale, destinationVersions, originWorkflow, mappedAssignedTo);
	saveItemMap(scope, item, map, originActiveVersion, last(destinationVersions));
	// injectFaults, GlobalLock.confirm, commit ou rollback: como hoje
}
```

## Riscos

Cada risco traz o teste que o prova. Os testes de portal rodam só com `bash test/run-tests.sh`, que para e restaura o Tomcat.

1. **Reflexão sobre construtores.** `check_structure.py` confere os nomes de métodos estáticos chamados por `ModuleBridge.invokeStatic`, mas não confere `getConstructor`. Um dos cinco pontos que constroem `DependencyContext` por reflexão, se esquecido, só falha em execução, com `NoSuchMethodException`.
   - **Mitigação:** o helper único em `ModuleBridge`, mais um `grep -rn "DEPENDENCY_CONTEXT\|CONTEXT =" test/java` na etapa 2.
   - **Prova:** `SpiRulesTest.handlersAreRegisteredInTheEngineAndRunInOrder` (`isSameRepository`), `MediaMigrationTest.clonesSharedMediaOnceRewritesFieldsAndHtmlRetriesAndUpdatesChangedBytes`, `DocumentMigrationTest.clonesSharedDocumentOnceRewritesReferencesRetriesAndUpdatesChangedBytes`, `CategorizationMigrationTest.resolvesPublishedAndActiveTermsByPathReusesDestinationAndRetriesMapConflict` e `CategorizationMigrationTest.deleteCloneDoesNotDeleteAnotherOriginRepositoryMapOrTerm`.
2. **Assinaturas usadas por reflexão que não podem mudar.** São elas `ItemProcessor.countMigrated` (`LifecycleTest`), `MigrationAdminQueries.listInstancesByService` (`AdminFormValidationTest`), `DependencyRules.validate`, os métodos do registro, `FileCloner.copy` (`SpiRulesTest`) e os métodos públicos de `MigrationApi`. O design não muda nenhuma delas.
   - **Prova:** `python3 test/check_structure.py`, que acusa `missing public static method`, mais `LifecycleTest`, `AdminFormValidationTest.creationRejectsDependencyRulesAndDuplicatePairs` e `SpiRulesTest.fileClonerCreatesNewFileWithSameBytesNameAndContentType`.
3. **Ordem de escrita na transação do item.** A ordem precisa continuar `returnToStartState`, versões (`addData` ou `updateData` e associações), `copyState` (com `flush` e SQL direto), `setPublishedVersion`, tags, autoria, mapa, `GlobalLock.confirm` e commit. Ao dividir `copyVersionsAndMetadata`, `copyState` precisa continuar antes de `setPublishedVersion`, para o `flush` de `writeState` não levar a publicação antes da hora. As sources e o `FileCloner` passam a ser criados antes, mas são só leitura.
   - **Prova:** `WorkflowMigrationTest.copiesPublishedActiveWorkflowAuthorshipAndCommentsWithoutDuplicates`, `WorkflowMigrationTest.nonAdminWithOnlyStartStateRoleCanUpdateRestrictedClone`, `WorkflowMigrationTest.sourceWithoutWorkflowInAWorkflowInstanceIsUpdatedAndUndone` e `SimpleMigrationTest.updateModeReplacesMappedVersionAndRecreatesDeletedDestination`.
4. **Transação por item e nova tentativa.** Escopo, warnings, `ItemDependencies` e `FileCloner` nascem dentro de `processOnce`. Nenhum campo de `ItemProcessor` pode guardá-los, porque a nova tentativa carregaria warnings e arquivos temporários da tentativa desfeita.
   - **Prova:** as três `*MigrationTest` de repositório, com `RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE` (risco 1), `LifecycleTest.runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus` e `LifecycleTest.injectedFailureStopsWithErrorAndAnIncrementalRunCopiesOnlyWhatIsMissing`.
5. **Lista de warnings mutável e compartilhada.** É uma lista por escopo, compartilhada pelos contextos do item. Os warnings dos handlers são deduplicados (`DependencyContext.addWarning`); os da cópia não são (`MigrationScope.addWarning`). Trocar essa semântica muda o texto gravado em `lum_cmig_MigItemMap.warnings`, e nenhum teste conta duplicatas: a semântica é preservada por construção e revisada na etapa 1.
   - **Prova** de que os warnings continuam chegando ao mapa: `MediaMigrationTest.reusesTheSmallestIdAmongDuplicateDestinationFoldersAndWarns` (warning do handler), `SimpleMigrationTest.duplicateSlugIsSuffixedAndOriginOnlyMetadataIsNotCopied` (slug), `WorkflowMigrationTest.nonAdminWithOnlyStartStateRoleCanUpdateRestrictedClone` (`no workflow role`) e `DeleteUndoMigrationTest.undoStopsChildFirstAndCanRetryWhileKeepingNativeParentReference` (desfazer).
6. **Validação de null antes da transação.** Hoje `start` comita `RUNNING` e o lock antes de construir `MigrationTask`. Uma exceção no construtor deixaria a migração `RUNNING` com o lock preso; esse é o caso da regressão já corrigida. Por isso o construtor novo não valida, e a tarefa passa a ser criada dentro de `runInTransaction`, onde uma falha desfaz o lock. O `build()` de `MigrationScope` roda dentro da transação do item: uma falha vira erro do item, e `recordFailure` grava `STOPPED_WITH_ERROR` e libera o lock.
   - **Prova:** `LifecycleTest.malformedExecutionPropertiesFallBackToTheDefaultsAndTheRunFinishes` e `LifecycleTest.startRunsInBackgroundAsTheStarterAndFinishesWithOneMapRowPerContentLocale`.
7. **Derivações que trocam parâmetros por getters.** São elas: versões de origem a partir de `originLocale`; `Content` a partir de `ContentLocale`; conexão a partir da transação; `add` a partir de `destination == null`; `writeFile` a partir de arquivo não nulo, com o `missingFile` de documento antecipado; e o título de pasta não renomeada. Cada uma é equivalente pelo código atual. O único ponto que muda de lugar em relação ao locale da sessão é a montagem do mapa de mídia e de documento, que só lê campos da entidade e os principais de publicação.
   - **Prova:** `WorkflowMigrationTest.copiesPublishedActiveWorkflowAuthorshipAndCommentsWithoutDuplicates` (autoria publicada e ativa), os testes `...UpdatesChangedBytes` de mídia e de documento (arquivo regravado só quando os bytes mudam), `MediaMigrationTest.reusesMatchingDestinationFolderPathAndKeepsSameRepositoryReferences` (pastas), `CategorizationMigrationTest.addsEachTermLocaleToTheSameDestinationTerm` (`TermEntry`) e `SimpleMigrationTest.userPostFilesAreCopiedIntoNewDestinationFiles` (campo `file` com `fileCloner`).
8. **`MigrationRow` mutável como descrição da execução.** `ItemProcessor` copia os quatro valores para campos `final`. `MigrationApi` relê a linha depois de `markRunning` e `markUndoRunning`, para que `updateMode` e `operation` sejam os gravados, e não a linha lida antes da marcação.
   - **Prova:** `SimpleMigrationTest.updateModeReplacesMappedVersionAndRecreatesDeletedDestination` (start com `updateMode`), `FullMigrationTest.migratesSharedDependenciesAcrossChannelsThenUpdatesAndUndoesNews` (atualiza e desfaz) e `DeleteUndoMigrationTest.undoRemovesUnusedClonesAndKeepsReusedAndReferencedDependencies`.
9. **Pedido de mapeamento de widget.** `WidgetMappingRequest` precisa manter a ordem atual das verificações (permissão das instâncias, repositório, estado bloqueado, correspondência, item de origem), porque os testes conferem que a permissão é checada antes de revelar o estado.
   - **Prova:** `WidgetMappingAdminTest.saveActionChecksPermissionsBeforeReportingMigrationState`, `WidgetMappingAdminTest.saveActionRefusesUserWithoutEditPermissionOnDestinationRepository` e `WidgetMigrationTest.blocksWithoutMappingThenResumesAndRewritesEveryEmbed`.

## Ordem de implementação

A ordem é **etapa 1, depois etapa 2, depois etapa 3**. Cada etapa compila, passa em `python3 test/check_structure.py` e nos testes de portal da sua lista antes do commit.

**Regra de transição entre etapas:**

- Quando um chamador já convertido chama um método de uma etapa seguinte, ele passa os valores tirados dos getters do DTO. Por exemplo, na etapa 1 `copySingleVersion` chama o `applyAfterWrite` antigo com `scope.getConnection()`, ..., `dependencies.getFileCloner()`, `scope.isUpdateMode()` e `scope.getWarnings()`.
- Quando um chamador ainda não convertido chama um método já convertido, ele monta o DTO com os seus campos. O único caso é `MigrationTask` nas etapas 1 e 2: o método privado `describeMigration()` monta a `MigrationRow` (`id`, `serviceId`, instâncias e `updateMode`) que `ItemProcessor` e `undoRepositoryItem` recebem. A etapa 3 o remove.

**Etapa 1: DTOs e a cópia do item.**

- Cria `MigrationScope` e `VersionCopy` em `R.dto` e `ItemDependencies` em `R.copy`.
- Converte `ItemProcessor` inteiro: construtor, `processOnce`, prechecks, `copyVersions`, `copyLocaleMetadata`, `saveItemMap`, `copySingleVersion` e `writeVersionData`.
- Converte também `AuthorshipCloner.copy`, `MetadataPolicy.resolveUniqueSlug` e `isSlugInUse`, e `WorkflowCloner.resolveAssignedTo`.
- Converte a cadeia de `buildData`: `buildData`, `putIdentityParameters`, `tryHandleSpecialMetadataField`, `resolveFieldValue`, `resolveParentContentId` e `prepareClonedValue`. `buildDependencyContexts` sai, e `findHandler` e `hasHandler` vão para `ItemDependencies`.
- Em `MigrationTask`, muda só `migrateItems`, mais `describeMigration()`.
- **Testes:** `SimpleMigrationTest`, `WorkflowMigrationTest`, `MediaMigrationTest`, `DocumentMigrationTest`, `CategorizationMigrationTest`, `WidgetMigrationTest`, `TagMigrationTest`, `LifecycleTest` e `FullMigrationTest`.

**Etapa 2: contexto, resto de `ContentCopier`, handlers e desfazer.**

- Dá a `DependencyContext` o construtor `(scope, repositories, fileCloner)` e remove os de 7 e 10 parâmetros.
- `ItemDependencies.create` passa a construir os contextos diretamente, e `createDependencyContext` sai.
- Converte `applyAfterWrite`, `copyStandardAssociations`, `resolveStandardAssociationTarget` e `copyCategorizationAssociations`.
- Converte `MigrationUndoer.undoRepositoryItem` e `undoExistingRepositoryClone`, com `MigrationTask.undoSingleRepositoryItem` usando `describeMigration()`.
- Converte os métodos internos dos handlers: em mídia, `createOrUpdate`, `buildMediaData` e `syncMediaFile`; em documento, `write`; em categorização, `TermEntry`.
- Cria o helper `newDependencyContext` em `ModuleBridge` e atualiza os cinco pontos de reflexão, com `SpiRulesTest` dentro de `PortalFixture.inTransaction`.
- **Testes:** `SpiRulesTest`, as três `*MigrationTest` de repositório, `DeleteUndoMigrationTest`, `WidgetMigrationTest` e `FullMigrationTest`.

**Etapa 3: restante.**

- Dá a `MigrationTask` o construtor `(migration, runId, userId)`, criado dentro de `runInTransaction` com a linha relida em `start` e `undo`. As sobrecargas de 6 e 7 parâmetros e `describeMigration()` saem.
- Em `MigrationApi`: divide `validateCreationPairAndPermissions`, faz `insertStoppedMigration(connection, migration)`, cria `WidgetMappingRequest` e converte `resolveBlockedMigrationWidgetTarget` e `matchesBlockedMigrationWidgetTarget`.
- Converte `MigrationUndoer.deleteDestinationContentLocale`.
- **Testes:** `LifecycleTest`, `AdminFormValidationTest`, `WidgetMappingAdminTest`, `WidgetMigrationTest`, `DeleteUndoMigrationTest`, `PersistenceContractTest` e `FullMigrationTest`.

**Verificação final, ao fim da etapa 3:** repetir o inventário (script léxico ou `javap -p` sobre `target/classes`, descontando os métodos-ponte) e confirmar que nenhuma declaração de `src` tem mais de 5 parâmetros. Pelas tabelas acima, das 60 declarações sobram apenas as 23 mantidas no limite: as outras 7 com 5 parâmetros são absorvidas, e as 30 com 6 ou mais mudam. As declarações novas têm todas 5 parâmetros ou menos. No código atual, a verificação passa, e `check_style.py` a repete a cada execução das verificações estáticas.
