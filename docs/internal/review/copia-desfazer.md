# Review da cópia, do desfazer e das regras: copy, undo e validation

Este relatório registra o review da cópia de um item, do desfazer e das regras de criação do serviço `br.com.lumis.commons.contentmigrator.service.contentmigration` (raiz `R`). As referências `arquivo:linha` do módulo apontam para o código depois das correções. As da plataforma Lumis são relativas a `Lumis_PortalJava` e foram conferidas no fonte, lido em ISO-8859-1.

## Escopo revisado

Cada classe abaixo foi lida inteira antes do julgamento, com os critérios de PAT001: correção, concorrência, transação por item, SQL portável para MySQL, Oracle e SQL Server, permissões, tratamento de erro, liberação de recursos, clareza e Javadoc fiel ao código.

- `R.copy`: `ItemProcessor`, `ContentCopier`, `MetadataPolicy`, `WorkflowCloner` e `AuthorshipCloner`.
- `R.undo`: `MigrationUndoer`.
- `R.validation`: `DependencyRules`.

Para julgar os fluxos, também foram lidos, sem alteração, os pontos de outras áreas que estas classes usam ou que as chamam: os laços de `MigrationTask` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:184` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:255`), `GlobalLock.confirm` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/lock/GlobalLock.java:124`), `RepositoryCloner.isUniqueMapConflict` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/RepositoryCloner.java:289`), o `deleteClone` dos handlers de mídia, documento e categoria e `FileCloner.close`.

### Fluxo do item e fronteira da transação

`ItemProcessor.processOnce` faz tudo numa transação: lê a linha do mapa, carrega o `ContentLocale` de origem, valida o idioma no canal de destino, roda os `precheck` dos handlers, coloca um destino existente no estado inicial do workflow, grava as versões com `addData` ou `updateData`, aplica associações, tags, estado do workflow, versão publicada e autoria, grava a linha de `lum_cmig_MigItemMap` e só então confirma o lock (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:227`) e faz o commit (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:232`). Os clones de repositório e as linhas de mapa deles são gravados na mesma transação, pelo `resolve` dos handlers.

- O caminho `SKIPPED` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:183`) só leu dados, e o `close` da transação faz o rollback.
- Com o lock perdido, o item é desfeito por `rollback` e volta `LOCK_LOST`. `GlobalLock.confirm` trava a linha de `lum_cmig_MigLock` até o fim da transação, e o flush do Hibernate no commit acontece com essa trava. Outra execução não assume o lock entre a confirmação e o commit. `LifecycleTest.runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/LifecycleTest.java:486`) cobre o caso.
- Os arquivos temporários de `FileCloner` são apagados no `close`, que roda depois do commit ou do rollback, como o Javadoc da classe exige.
- A nova tentativa de `process` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:156`) abre outra transação, porque a primeira já foi fechada pelo `try` com recurso.
- `MigrationUndoer.undoContent` e `undoRepositoryItem` seguem o mesmo desenho: um item por transação e lock confirmado antes do commit (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:474` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:598`).

### Idempotência e interrupção

| Cenário | Conclusão |
| --- | --- |
| Segunda execução em `ADD_ONLY` | É idempotente. Item com mapa e destino presente volta `SKIPPED`. Conteúdo, linha de workflow, clones e linhas de mapa entram juntos no commit do item, então não há item copiado sem mapa nem mapa sem item. `LifecycleTest.injectedFailureStopsWithErrorAndAnIncrementalRunCopiesOnlyWhatIsMissing` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/LifecycleTest.java:355`) comprova que a execução seguinte copia só o que falta. |
| Interrupção dentro de um item (erro, queda da JVM, lock perdido) | O rollback desfaz o item inteiro. A escrita direta em `lum_WorkflowMetaData` usa a conexão da transação e também é desfeita. Numa queda da JVM, só os arquivos temporários de `FileCloner` ficam no diretório temporário, sem efeito no banco. |
| Interrupção entre itens (`stop`, erro em outro item, lock vencido) | Os itens confirmados ficam com mapa, e a próxima execução os pula. O pedido de parada é lido entre itens, e o item em curso termina antes. |
| Destino apagado à mão | `hasDestinationContentLocale` devolve falso, o item é copiado de novo e a linha de mapa é atualizada. `countMigrated` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:579`) já não conta esse item. |
| Segunda execução em `UPDATE` | Não é idempotente no histórico: cada execução grava versões novas mesmo sem mudança na origem (achado adiado 1). |
| Origem apagada durante a execução | A migração para com erro (achado adiado 2). A execução seguinte não descobre mais o item e segue. |
| Desfazer repetido ou interrompido | Cada linha de mapa sai junto com o seu conteúdo, na mesma transação. A execução seguinte lista só o que sobrou. Um item retido por referência mantém a linha, e `appendWarning` não repete o aviso. |

### Workflow

- A cópia nunca executa transição. Os mapas de `addData` e `updateData` não levam os parâmetros de transição nem de responsável, então `WorkflowController#executeActionIfNecessary` não faz nada (`content/src/java/lumis/content/workflow/WorkflowController.java:79`). Por isso a falta de uma transição entre o estado inicial e o estado da origem não afeta a migração: o estado é gravado direto em `lum_WorkflowMetaData` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java:62`), sem action handler nem e-mail. A plataforma lê essa tabela por JDBC, sem cache (`WorkflowDaoJdbc#getContentWorkflowMetaData`, `content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:598`), e enxerga o valor gravado na mesma transação.
- As checagens nativas continuam valendo para quem iniciou a execução: permissão no estado inicial para criar a linha de workflow (`WorkflowManager#createNewContentWorkflowMetaData`, `content/src/java/lumis/content/workflow/WorkflowManager.java:671`) e permissão de edição no `updateData` (`WorkflowController#contentUpdated`, `content/src/java/lumis/content/workflow/WorkflowController.java:55`). `returnToStartState` atribui o item ao usuário da execução para que a regra de escopo `assignedTo` valha. `WorkflowMigrationTest.nonAdminWithOnlyStartStateRoleCanUpdateRestrictedClone` cobre o caso.
- `hasEffectiveWorkflowRole` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java:122`) segue a regra de `WorkflowDaoJdbc#getRolesByPrincipals` (`content/src/java/lumis/content/workflow/WorkflowDaoJdbc.java:522`): papéis permitidos menos papéis negados, sobre o principal e os grupos dele. O usuário de sistema tem todos os papéis, como em `WorkflowManager#getPrincipalRoles` (`content/src/java/lumis/content/workflow/WorkflowManager.java:312`).
- Divergência corrigida: o estado inicial era procurado pelo workflow da instância, e não pelo da fonte (achado corrigido 1).

### ContentLocale, ContentVersion e metadados

- `ContentLocale#getLockType` nunca devolve nulo (`content/src/java/lumis/content/core/ContentLocale.java:926`). O módulo recusa qualquer lock no destino (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:313`), o que é mais estrito que `ContentTableSource#updateData`, que aceita o dono do lock (`content/src/java/lumis/content/table/ContentTableSource.java:693`). A recusa é deliberada: `SimpleMigrationTest.updateModeReplacesMappedVersionAndRecreatesDeletedDestination` espera o erro.
- `ContentMetaDataController#contentInserted` publica a versão nova só quando o estado permite (`content/src/java/lumis/content/table/ContentMetaDataController.java:241`). Depois a cópia aponta a versão publicada do destino para a cópia da versão publicada da origem, ou para nenhuma (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:410`, em `copyLocaleMetadata` desde 2026-10-18).
- Com versionamento, cada `updateData` acrescenta uma versão baseada na ativa (`content/src/java/lumis/content/table/ContentMetaDataController.java:750`).
- `MetadataPolicy.isSlugInUse` repete a consulta de `ContentLocale#isSlugInUse` (`content/src/java/lumis/content/core/ContentLocale.java:1308`).
- `ContentLocale#addComment` grava o usuário e a data correntes (`content/src/java/lumis/content/core/ContentLocale.java:451`). `AuthorshipCloner` sobrescreve os dois com os da origem e não repete comentário de mesmo texto, autor e data.
- `ContentLocale#addTag` resolve o nome no repositório de tags do destino (`content/src/java/lumis/content/core/ContentLocale.java:1004`).
- Os campos `publishToServiceInstances`, `publishToPrincipals` e `publishToSocialNetwork` são gerados com `getData="false"` (`content/src/java/lumis/content/service/ContentService.java:561`, `content/src/java/lumis/content/service/ContentService.java:568` e `content/src/java/lumis/content/service/ContentService.java:575`), e `SingleColumnTableSourceField#getTableMapping` não os seleciona (`doui/src/java/lumis/doui/table/field/SingleColumnTableSourceField.java:68`). A linha lida da origem nunca traz esses valores (achado corrigido 3 e achado adiado 6).

### Ordenação filho-primeiro do desfazer

`contentItems` ordena os conteúdos do mapa com `orderTopologicallyChildFirst` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:214`). As arestas vêm de `lum_HierarchicalContent` (filho para pai) e de `lum_ContentAssociation` (dono da associação para o alvo), e as folhas saem primeiro. Um pai só entra na fila depois de todos os filhos migrados. Um ciclo vai para o fim, e `isContentReferenced` retém os conteúdos ainda referenciados, com aviso na linha do mapa. Nos clones de repositório, a ordem segue a pasta pai de mídia e documento e o termo pai de categoria. O item de um termo clonado é o id do conteúdo dele (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:337`), e por isso `queryParentTerms` filtra por `contentId`. `DeleteUndoMigrationTest.undoStopsChildFirstAndCanRetryWhileKeepingNativeParentReference` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/DeleteUndoMigrationTest.java:254`) cobre a ordem, a nova tentativa e a retenção.

### SQL nos três bancos

- `order by case ... end`, `count(*)`, `exists (select 1 ...)` correlacionado e parâmetros por `?` funcionam nos três bancos. Os `in (...)` são montados em lotes de 400 valores, abaixo do limite de 1000 itens do Oracle e de 2100 parâmetros do SQL Server.
- O Oracle grava `''` como nulo. A coluna `warnings` aceita nulo, e `appendWarning` trata o nulo como vazio.
- Correção: a consulta de referência do desfazer não tinha `from` (achado corrigido 4). Observação para o review de `R.dependency`: a mesma forma, `select case when exists (...) then 1 else 0 end` sem `from`, existe em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:361`, `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:427` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:129`, fora do escopo deste review.

### Regras de criação, permissões, erros e recursos

- `DependencyRules` não tem defeito: cada regra usa `getServiceInstanceByDependency`, `getServiceInstanceWorkflow` e a definição da fonte. A lista de violações volta inteira em `RulesViolatedException`. As regras só rodam em `create` (achado adiado 5).
- A cópia roda como o usuário que iniciou a execução, com as checagens nativas citadas acima. A escrita direta do estado da origem dispensa a permissão no estado final, por decisão registrada em `docs/architecture/content-metadata-migration.md:196` e `docs/architecture/content-metadata-migration.md:260`.
- Todo `PreparedStatement` e `ResultSet` está em `try` com recurso, toda transação é fechada, e `FileCloner` é fechado depois do commit.
- Nenhuma exceção é engolida nas três áreas. As únicas capturas são `PortalObjectNotFoundException`, convertida em aviso ou em "item ausente", e o conflito de chave única, que leva a uma nova tentativa.

## Achados corrigidos

### 1. Atualização e desfazer falhavam em fonte sem workflow numa instância com workflow

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java:41` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java:55`. Chamadores: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:218` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:558`.
- Impacto: `returnToStartState` buscava o workflow com `IWorkflowManager#getServiceInstanceWorkflow`, que é o da instância. A plataforma, porém, só cria linha em `lum_WorkflowMetaData` para conteúdo de fonte que declara `<workflow/>`: `WorkflowManager#getSourceWorkflow` (`content/src/java/lumis/content/workflow/WorkflowManager.java:485`) usa `ContentTableSource#getWorkflowConfig` (`content/src/java/lumis/content/table/ContentTableSource.java:246`), que só devolve o workflow quando a fonte tem os campos de workflow (`content/src/java/lumis/content/table/ContentTableSource.java:263`). Num serviço com fontes mistas, como `lumis.service.link`, cuja fonte `category` não declara workflow (`contentservices/src/java/lumis/service/link/douidefinition.xml:5`), a atualização e o desfazer de uma categoria terminavam em `STOPPED_WITH_ERROR` com `Expected one workflow metadata row`, porque `writeState` exige uma linha.
- Correção: `sourceWorkflow` obtém o workflow pela fonte do conteúdo, como `WorkflowManager#getSourceWorkflow`. Conteúdo sem workflow fica como está. O parâmetro `transaction` de `copyState`, que não era usado, saiu.
- Nota de 2026-10-18: o chamador em `ItemProcessor.processOnce` agora fica dentro do `try` do `FileCloner`, depois de montar o `MigrationScope`; `returnToStartState` e `copyState` não mudaram.
- Teste: `WorkflowMigrationTest.sourceWithoutWorkflowInAWorkflowInstanceIsUpdatedAndUndone` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/WorkflowMigrationTest.java:232`) copia, atualiza e desfaz uma categoria de links numa instância com o workflow padrão.

### 2. Fonte sem campo de idioma recebia o idioma padrão do portal

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:194`.
- Impacto: numa fonte sem `<multiLanguage/>`, `ContentMetaDataController#insertMetaData` usa o idioma da sessão como idioma do conteúdo novo e como idioma padrão do `Content` (`content/src/java/lumis/content/table/ContentMetaDataController.java:261` e `content/src/java/lumis/content/table/ContentMetaDataController.java:274`). A execução roda numa sessão personificada criada sem sessão corrente, e por isso com o idioma padrão do portal (`portal/src/java/lumis/portal/authentication/AuthenticationManager.java:432` e `portal/src/java/lumis/portal/authentication/SessionConfig.java:175`). Um item `en_US` de `lumis.service.userpost` chegava ao destino como `pt_BR`. A validação de idioma do canal conferia o idioma da origem, e não o que era gravado.
- Correção: antes de gravar, o item põe o idioma da origem na sessão, como o desfazer já fazia (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:556`).
- Teste: `SimpleMigrationTest.sourceWithoutLanguageFieldKeepsTheOriginLocale` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/SimpleMigrationTest.java:471`).

### 3. Público e instâncias de publicação da origem não eram copiados

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:215`, `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:227` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/MetadataPolicy.java:162`.
- Impacto: `publishToPrincipals` estava na lista de campos tratados à parte e nunca chegava ao `addData`. A publicação nova nasce para todos os usuários (`ContentVersion#addPublication`, `content/src/java/lumis/content/core/ContentVersion.java:615`). Um conteúdo restrito a um grupo na origem ficava visível para todos no destino, ao contrário do que a arquitetura define (`docs/architecture/content-metadata-migration.md:215`). Pela mesma causa, `resolvePublicationTargets` nunca recebia valor: os campos de publicação têm `getData="false"` e não vêm na linha lida. Um conteúdo da origem publicado só em outra instância ficava publicado na instância de destino.
- Correção: `buildData` recebe a versão de origem e lê o público e as instâncias da primeira publicação dela, a mesma que `ContentMetaDataController#setContentVersionProperties` grava (`content/src/java/lumis/content/table/ContentMetaDataController.java:492`). O público é passado por parâmetro. As instâncias seguem a regra que já existia: só o destino quando a origem era alvo, nenhuma instância quando não era. `MetadataPolicy` passa `PublishToPrincipals` para a lista de parâmetros copiados.
- Nota de 2026-10-18: `buildData(scope, dependencies, version)` recebe a versão de origem dentro do `VersionCopy`, e `tryHandleSpecialMetadataField(scope, dependencies, version, data, metadata)` lê a primeira publicação de `version.getOriginVersion()` só nos ramos `PublishTo*`. O público deixou de ser um parâmetro solto.
- Teste: `SimpleMigrationTest.publicationPrincipalsAndTargetsComeFromTheOriginPublication` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/SimpleMigrationTest.java:423`).

### 4. Consulta de referência do desfazer sem `from`

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:500`.
- Impacto: `isContentReferenced` usava `select case when exists (...) then 1 else 0 end` sem `from`. O Oracle anterior à versão 23ai recusa essa forma (ORA-00923), e no Oracle todo desfazer de conteúdo terminava com erro de SQL. A própria plataforma escreve `from dual` nesses casos, por exemplo em `portal/src/java/lumis/portal/healthmonitor/HealthCheckGeneral.java:1679`.
- Correção: a consulta conta a linha de `lum_Content` do conteúdo com as duas condições `exists` correlacionadas, uma forma aceita pelos três bancos.
- Teste: não é testável no ambiente local, que só tem MySQL. O README registra que Oracle e SQL Server não estão na matriz de integração (`README.md:121`). No MySQL, a forma nova é coberta por `DeleteUndoMigrationTest.undoStopsChildFirstAndCanRetryWhileKeepingNativeParentReference`, que retém um pai ainda referenciado.

### 5. Slug cortado numa barra ficava em formato inválido

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/MetadataPolicy.java:106`.
- Impacto: quando o slug ocupado no destino tinha perto de 100 caracteres, o corte para caber o sufixo podia terminar em `/`. O candidato `.../-1` viola o formato de `ContentVersion#setSlug` (`content/src/java/lumis/content/core/ContentVersion.java:657`), e a migração parava com `STR_CONTENT_SLUG_WRONG_FORMAT`.
- Correção: o corte descarta a barra final antes do sufixo. O limite de 100 caracteres virou a constante `MAX_SLUG_LENGTH`, com a origem citada (`content/src/java/lumis/content/core/ContentVersion.java:652`).
- Teste: `SimpleMigrationTest.suffixedSlugCutAtASlashKeepsTheNativeSlugFormat` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/SimpleMigrationTest.java:395`).

### 6. Javadoc diferente do código

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:77`, `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:92`, `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:156` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:82`.
- Impacto: o construtor citava o modo `ADD_AND_UPDATE`, que não existe; o valor aceito pela API é `UPDATE`. `discover` dizia que a ordem é por conteúdo, mas a consulta põe primeiro os idiomas padrão de todos os conteúdos. `process` falava em inserção concorrente e não citava os três resultados. `findMappedDestinationContentId` não dizia que também serve a pai e associação, nem que não confere se o destino existe.
- Correção: os quatro textos descrevem o comportamento real, e `MetadataPolicy` passou a dizer o que é omitido de propósito.
- Nota de 2026-10-18: o construtor passou a `ItemProcessor(String runId, SessionConfig session, MigrationRow migration)`; o Javadoc continua citando `UPDATE` como o único valor de atualização, e um `updateMode` nulo vira `ADD_ONLY`.
- Teste: não se aplica (Javadoc).

### 7. Clareza

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:313`, `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:403` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:620`.
- Impacto: o teste de lock comparava `getLockType` com nulo, valor que a plataforma nunca devolve. `queryParentTerms` chamava de `itemIds` uma lista de ids de conteúdo. A chamada a `deleteClone` não dizia que o handler preserva o que a migração não criou e apaga as linhas de mapa.
- Correção: a comparação redundante saiu, o parâmetro virou `termContentIds` com Javadoc, e a chamada ganhou um comentário.
- Teste: não se aplica (clareza, sem mudança de comportamento).

## Achados adiados

### 1. `UPDATE` grava versões novas mesmo sem mudança na origem

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:294`.
- Impacto: em `UPDATE`, todo item mapeado é regravado a cada execução. Numa fonte com versionamento, cada execução acrescenta uma ou duas versões ao destino, e uma execução interrompida e repetida grava de novo os itens já atualizados. O histórico do destino cresce sem mudança real.
- Pergunta que decide: o modo `UPDATE` deve pular o item cuja versão ativa da origem não mudou desde a última cópia, comparando `originItemId` do mapa e a data de modificação? Ou regravar sempre é o comportamento esperado?

### 2. Origem apagada durante a execução interrompe a migração

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:186`.
- Impacto: a lista de itens é lida no início. Se um editor apaga um conteúdo da origem antes de o item ser processado, `ContentManager#getContentLocale` lança `PortalObjectNotFoundException` (`content/src/java/lumis/content/core/ContentManager.java:167`), e a execução termina em `STOPPED_WITH_ERROR`. Uma nova execução segue sem esse item.
- Pergunta que decide: um item que sumiu da origem deve ser pulado com aviso, ou o erro deve continuar parando a execução para chamar a atenção do operador?

### 3. Referência a conteúdo ainda não migrado fica vazia

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:284` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:393`.
- Impacto: pai hierárquico e associação são remapeados pelo mapa. Se o alvo vem depois na ordem de descoberta, que é por data de criação, a referência fica vazia com aviso. Numa nova execução em `ADD_ONLY`, o item é pulado e a referência nunca é preenchida; só `UPDATE` a corrige.
- Pergunta que decide: a descoberta deve ordenar os pais antes dos filhos e os alvos antes das associações, ou deve haver uma segunda passada que complete as referências? Ou basta documentar que é preciso rodar `UPDATE`?
- Nota de 2026-10-18: os dois avisos agora são gravados por `MigrationScope.addWarning`, em `resolveParentContentId(scope, value)` e `resolveStandardAssociationTarget(scope, associated)`; o comportamento é o mesmo.

### 4. Campo de lookup entre fontes do mesmo serviço vai com o id da origem

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:51` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:263`.
- Impacto: um campo `string` com `lookupRelationId` para outra fonte do mesmo serviço, como `categoryId` de `lumis.service.link` (`contentservices/src/java/lumis/service/link/douidefinition.xml:24`), é copiado como texto simples. O link de destino aponta para a categoria da instância de origem, que o filtro por instância esconde no destino.
- Pergunta que decide: a migração deve remapear esses campos pelo mapa, com a fonte referenciada copiada antes? Ou `DependencyRules` deve recusar na criação os serviços com relações entre fontes de conteúdo?

### 5. Regras de criação não são conferidas de novo no início

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/validation/DependencyRules.java:76`, chamado só em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:247`.
- Impacto: se o workflow ou um repositório de uma das instâncias muda depois da criação, `start` roda mesmo assim. Com workflows diferentes, `copyState` grava no destino um `stateId` que o workflow do destino não conhece.
- Pergunta que decide: `start` deve repetir `DependencyRules.check` e recusar a execução com as violações? A mudança é em `MigrationApi`, área de outro review.

### 6. O aviso de rede social nunca é emitido

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:199`.
- Impacto: o aviso "Social network publications were not copied." depende do valor de `publishToSocialNetwork` na linha lida, que nunca vem (`getData="false"`). Nenhuma publicação em rede social é copiada, como previsto, mas o operador não é avisado.
- Pergunta que decide: o aviso deve ser calculado pelas integrações do `ContentLocale` de origem (`ContentMessageIntegration#getContentMessageIntegrations`), sem teste automatizado, já que o ambiente local não tem rede social configurada? Ou o aviso deve sair do código?

## Verificações executadas

Comandos rodados a partir da raiz do módulo, com o resultado obtido. Os rótulos `VAL-*` nomeiam as verificações desta rodada de revisão; quando o comando importa, ele aparece entre parênteses.

- Prova de regressão antes da correção: com os testes novos e o código antigo implantado, `bash test/run-tests.sh SimpleMigrationTest WorkflowMigrationTest` terminou com `Tests run: 11, Failures: 4`. As quatro falhas foram as previstas: `expected:<en_US> but was:<pt_BR>` (achado 2), `STR_CONTENT_SLUG_WRONG_FORMAT` para o slug terminado em `/-1` (achado 5), `expected:<[00000000D00000000000000000000001]> but was:<[00000000C00000000000000000000001]>`, isto é, o grupo de todos os usuários no lugar do administrador (achado 3), e `Expected one workflow metadata row` na atualização da categoria de links (achado 1). Os outros 7 testes passaram.
- Depois da correção, todas as verificações terminaram com saída 0. `VAL-COMPILE` (`mvn clean test-compile`) compilou sem erro. `VAL-STYLE-COPY` verificou os 7 arquivos de `copy`, `undo` e `validation` sem violações, e `VAL-STRUCTURE-TESTS` terminou com `PASS structure: 0 error(s), 0 warning(s)`. As suítes de portal deram `VAL-STAGE1` com `OK (8 tests)`, `VAL-WORKFLOW` com `OK (3 tests)`, `VAL-DELETE-UNDO` com `OK (3 tests)`, `VAL-LIFECYCLE` com `OK (7 tests)` e `VAL-SPI` com `OK (7 tests)`. `VAL-REVIEW` validou este relatório no perfil `review`, e `test/check_tone.py` não apontou termo inadequado.
- `bash test/format.sh` sobre os arquivos Java alterados só juntou a chamada a `AuthorshipCloner.copy`, que passou a caber em 170 colunas. `python3 test/check_style.py` sobre os mesmos arquivos e sobre os testes alterados terminou sem violações.
