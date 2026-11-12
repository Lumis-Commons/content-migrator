# Review do núcleo: api, execution, config, lock, dao, dto, exception e security

Este relatório registra o review do núcleo do serviço `br.com.lumis.commons.contentmigrator.service.contentmigration` (raiz `R`): a fachada `MigrationApi`, a execução em segundo plano, o lock global, os DAOs, os DTOs, as exceções e as permissões. As referências `arquivo:linha` apontam para o código depois das correções; as da plataforma Lumis são relativas a `Lumis_PortalJava`.

## Escopo revisado

Cada classe abaixo foi lida inteira antes do julgamento, com os critérios de PAT001: correção, concorrência, transação por item, SQL portável para MySQL, Oracle e SQL Server, permissões, tratamento de erro, liberação de recursos, clareza e Javadoc fiel ao código.

- `R.api`: `MigrationApi` (`create`, `get`, `list`, `start`, `stop`, `delete`, `undo`, `awaitTermination` e `saveWidgetMapping`).
- `R.execution`: `MigrationExecutor`, `MigrationTask` e `RunStatusUpdater`.
- `R.config`: `ExecutionSettings`. `R.lock`: `GlobalLock`. `R.security`: `MigrationPermissions`.
- `R.dao`: `MigrationDao`, `ItemMapDao`, `RepositoryMapDao`, `WidgetMapDao` e `Transactions`.
- `R.dto`: as dez classes de linha e de valor. `R.exception`: as quatro exceções.

Para julgar os fluxos, também foram lidos, sem alteração, os pontos de outras áreas que o núcleo chama: o commit condicionado do item em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:227`, os do desfazer em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:470` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:596`, a regra de par compartilhado em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:85`, o `userId` passado pela administração em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/processaction/MigrationProcessActionHandler.java:57` e a leitura de status em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/query/MigrationStatusQueries.java:83`.

### Ciclo de vida da execução

`start` e `undo` fazem, numa só transação, a checagem de permissão, `GlobalLock.acquire` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/lock/GlobalLock.java:76`) e `markRunning` ou `markUndoRunning`, condicionados ao `runId` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:36` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:72`). Depois do commit, `MigrationExecutor.submit` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationExecutor.java:29`) entrega a `MigrationTask` ao executor do portal; se o executor recusar a tarefa, a execução é encerrada com erro e o lock é liberado na mesma chamada. A tarefa copia ou desfaz um item por transação, confirma o lock antes de cada commit e grava contadores, heartbeat e status final em transações curtas condicionadas ao `runId`. O pedido de parada é lido entre itens.

Todas as escritas de uma execução dependem do `runId`: `markRunning`, `markUndoRunning`, `updateCounts`, `GlobalLock.heartbeat`, `RunStatusUpdater.finish` e os commits de item em `ItemProcessor` e `MigrationUndoer`. Uma execução que perdeu o lock não grava mais nada; o teste `runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus` de `LifecycleTest` comprova o caso.

### Corridas analisadas

| Corrida | Conclusão |
| --- | --- |
| `start` × `start`, `start` × `undo` | O `update` condicional da única linha de `lum_cmig_MigLock` serializa as duas chamadas. A segunda relê a linha já gravada e recebe `LOCKED`. |
| `start` × `stop` | Um `stop` anterior ao commit do `start` não altera nada, porque `requestStop` exige `status = RUNNING`. Um `stop` posterior é atendido entre itens. `markRunning` limpa um pedido antigo, e `finish` limpa o pedido atendido. |
| `start` ou `undo` × `delete` | As duas chamadas escrevem a mesma linha de `lum_cmig_Migration`, e o lock de linha do banco as serializa. Se o `delete` vence, `markRunning` atualiza zero linhas, lança `SQLException` e o rollback desfaz também a aquisição do lock. Se o `start` vence, `lockStoppedMigration` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dao/MigrationDao.java:191`) não encontra a linha parada e o `delete` recebe `LOCKED`. |
| `stop` × fim da execução | `finish` grava o status e zera `stopRequested` na mesma transação em que libera o lock; um `stop` posterior não encontra `RUNNING` e não tem efeito. |
| `undo` × `delete` | Mesma serialização de `start` × `delete`: enquanto o desfazer roda, o `delete` recebe `LOCKED`. |
| `create` × `create` do mesmo par | A chave única `LUM_UN_CMIG_MIGRATION_PAIR` impede a duplicação. A segunda chamada falha com erro de SQL em vez de `DUPLICATE_PAIR`; foi aceito, porque nenhum dado fica inconsistente. |
| `create` × `delete` com par de repositórios compartilhado | `isRepositoryPairUsedByAnother` não enxerga a migração ainda não confirmada, e o `delete` apaga os mapas de clones do par. O resultado é o mesmo de um `delete` seguido de `create`: a próxima execução clona de novo. A linha de `lum_cmig_MigRepoMap` pode faltar, mas nenhum código a lê (ver os achados adiados). Foi aceito sem correção. |
| Item × heartbeat × contadores da mesma execução | Depois da correção 4, toda escrita da execução trava `lum_cmig_MigLock` antes de `lum_cmig_Migration`. Das duas tabelas de controle, o commit do item trava apenas `lum_cmig_MigLock`, e por último. `delete` e `stop` não tocam `lum_cmig_MigLock`. Não há ciclo de espera entre as transações do módulo. |

### SQL nos três bancos

Os tipos foram conferidos no XSL da plataforma. Em `portal/src/java/lumis/database/db_oracle.xsl:18`, `string` vira `NVARCHAR2(n)` e `dateTime` vira `DATE` (`portal/src/java/lumis/database/db_oracle.xsl:64`). Em `portal/src/java/lumis/database/db_sqlserver.xsl:36`, `string` vira `NVARCHAR(n)` e `dateTime` vira `DATETIME` (`portal/src/java/lumis/database/db_sqlserver.xsl:64`). Em `portal/src/java/lumis/database/db_mysql.xsl:18`, `string` vira `VARCHAR(n)` e `dateTime` vira `DATETIME` (`portal/src/java/lumis/database/db_mysql.xsl:50`).

- Os DAOs usam só SQL comum aos três bancos: `count(*)`, `exists (select 1 ...)`, `in (select ...)`, `CURRENT_TIMESTAMP` com `from lum_cmig_MigLock` (o Oracle exige `from`), parâmetros por `?` e booleanos por `setBoolean`/`getBoolean` sobre `TINYINT` ou `NUMBER(1)`. Não há `LIMIT`, `TOP`, concatenação por `||` nem literal booleano.
- Nenhuma coluna do módulo usa palavra reservada dos três bancos, e o SQL repete os nomes de tabela exatamente como `databasedefinition.xml` os declara, o que importa no MySQL em Linux.
- Limites das colunas de erro: `errorMessage` tem 1000 caracteres nos três bancos (`NVARCHAR2` e `NVARCHAR` contam caracteres), e o corte em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:178` usa unidades UTF-16, que nunca ultrapassam esse limite. `errorType` tem 40 caracteres, e o maior valor gravado, `WIDGET_MAPPING_REQUIRED`, tem 23. `errorStack` é `text` (`CLOB`, `NTEXT` ou `LONGTEXT`).
- O lock compara `heartbeat` com o relógio do próprio banco (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/lock/GlobalLock.java:84`), o que mantém o cálculo de lock vencido coerente mesmo quando o fuso do banco difere do fuso da JVM.
- O portal usa `READ COMMITTED` (`portal_install/lumisdata/config/lumishibernate.cfg.xml:36`), o que vale para as conclusões das corridas acima.
- A única mudança de SQL deste review está em `updateCounts` (correção 4) e não altera tabela nem coluna. Por isso `databasedefinition.xml` ficou como estava. O componente não tem script de upgrade, só `component-install-script.xml` e `component-uninstall-script.xml`.

### Permissões, erros e recursos

- `MigrationPermissions.canViewOrigin` e `canEditDestination` personificam o usuário e chamam `ServiceInstanceAclManager#checkServiceInstancePermission` (`portal/src/java/lumis/portal/serviceinstance/acl/ServiceInstanceAclManager.java:146`). `hasServiceInstancePermission` chama a sobrecarga com `userId` (`portal/src/java/lumis/portal/serviceinstance/acl/ServiceInstanceAclManager.java:155`). As duas formas dão o mesmo resultado. `AclManager#checkPermission` com sessão (`portal/src/java/lumis/util/security/acl/AclManager.java:277`) só acrescenta o grupo de administradores quando a sessão tem administração de nível 2, e a sessão criada por `AuthenticationManager#impersonate` (`portal/src/java/lumis/portal/authentication/AuthenticationManager.java:423`) nasce sem esse nível (`portal/src/java/lumis/portal/authentication/SessionConfig.java:538`). Sobra a regra de `AclManager#checkPermission` por usuário (`portal/src/java/lumis/util/security/acl/AclManager.java:301`).
- A personificação aninhada dentro de `runInTransaction` é segura: `impersonate` empilha a sessão e `AuthenticationManager#endImpersonation` (`portal/src/java/lumis/portal/authentication/AuthenticationManager.java:441`) desempilha a mesma instância.
- `start` e `undo` aceitam `userId` no `Map`. O único chamador de produção, a ação de processo, passa o usuário autenticado (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/processaction/MigrationProcessActionHandler.java:57`). `stop`, `delete` e `saveWidgetMapping` usam o usuário da sessão.
- As mensagens de `MigrationApiException` trazem apenas códigos, ids de usuário e de instância. `errorMessage` e `errorStack` só saem para quem administra o par (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/query/MigrationAdminQueries.java:180`), e o REST de status não os expõe.
- Erro engolido: há só três descartes, todos intencionais e registrados em log. O primeiro é a falha do heartbeat, que o próximo pulso repete. O segundo é a perda do lock; nesse caso, quem grava o status é a execução que assumiu o lock. O terceiro é a falha ao gravar o erro final; nesse caso, o lock vence depois de cinco minutos. `MigrationExecutor.submit` anexa a falha secundária com `addSuppressed`.
- Recursos: todo `PreparedStatement` e `ResultSet` está em `try` com recurso. Toda transação é fechada, e `TransactionHibernate#close` faz rollback da transação ainda ativa (`portal/src/java/lumis/portal/dao/hibernate/TransactionHibernate.java:320` e `portal/src/java/lumis/portal/dao/hibernate/TransactionHibernate.java:342`). O temporizador de heartbeat é cancelado no `finally` da tarefa.

## Achados corrigidos

### 1. `create` gravava qualquer `updateMode`

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:83` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:125`.
- Impacto: `create` gravava sem validar qualquer texto em `updateMode`. Um valor como `FOO` ficava na migração, e o `start` seguinte, sem `updateMode`, o tratava como `UPDATE`, porque `ItemProcessor.isUpdateMode` só reconhece `ADD_ONLY` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:289`). Assim, conteúdos já migrados no destino eram sobrescritos sem que ninguém tivesse pedido atualização. Um texto vazio violava `isNotNull` no Oracle, que grava `''` como nulo, e um valor que não fosse `String` causava `ClassCastException` em `create` e em `start`.
- Correção: `readUpdateMode` valida o parâmetro nas duas operações e lança `INVALID_UPDATE_MODE` para qualquer valor diferente de `ADD_ONLY` e `UPDATE`. Esse é o mesmo código que `start` já usava. A ausência continua valendo `ADD_ONLY` em `create` e "manter o atual" em `start`.
- Teste: `PersistenceContractTest.createAcceptsOnlyTheSupportedUpdateModes` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/contract/PersistenceContractTest.java:279`).

### 2. Datas da migração com tipo diferente no MySQL

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dao/MigrationDao.java:135` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/MigrationRow.java:4`.
- Impacto: o DAO lia `heartbeat`, `createdDate`, `startDate` e `endDate` com `getObject`. O MySQL Connector/J 9.5 do portal devolve `LocalDateTime` para `DATETIME`, o que foi confirmado com uma consulta a `CURRENT_TIMESTAMP` no MySQL local. No MySQL, `MigrationApi.get` devolvia as datas como `2026-10-17T11:39:47`; no Oracle e no SQL Server, como milissegundos. Pior, `MigrationStatusQueries.isStale` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/query/MigrationStatusQueries.java:83`) só aceita `Number`. Por isso, no MySQL o REST de status mostrava toda migração em execução como `STOPPED_WITH_ERROR`/`INTERRUPTED`.
- Correção: `readEpochMillis` lê as datas com `getTimestamp`, que tem o mesmo tipo nos três drivers. `MigrationRow` passa a tipar as quatro datas como `Long`, em milissegundos desde a época, e o Javadoc da classe e da fachada diz isso. As chaves do `Map` da API não mudaram.
- Teste: `PersistenceContractTest.migrationDatesAreEpochMilliseconds` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/contract/PersistenceContractTest.java:301`) e as asserções de `startDate`, `endDate` e `heartbeat` em `LifecycleTest.startRunsInBackgroundAsTheStarterAndFinishesWithOneMapRowPerContentLocale` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/LifecycleTest.java:324`).

### 3. Propriedade de execução malformada prendia a migração em `RUNNING`

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/config/ExecutionSettings.java:42`. A leitura ocorre no construtor de `MigrationTask` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:53` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:55`), chamado por `start` depois do commit (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:412`, ver a nota abaixo).
- Impacto: com `contentmigrator.contentmigration.heartbeatSeconds` ou `itemTimeLimitSeconds` sem número inteiro (por exemplo, `30s`), `Integer.parseInt` lançava `NumberFormatException` depois que `start` já tinha confirmado `RUNNING` e tomado o lock. Nenhuma tarefa rodava, ninguém renovava o heartbeat, `delete` recusava a migração com `LOCKED` e `stop` não tinha efeito; o lock só vencia depois de cinco minutos.
- Correção: `intProperty` passa a usar o padrão quando o valor não é inteiro, como `Integer.getInteger`, e registra um aviso com o nome da propriedade. O Javadoc da classe explica o motivo: as propriedades são lidas depois que a execução tomou o lock.
- Nota de 2026-10-18: o construtor passou a `MigrationTask(MigrationRow migration, String runId, String userId)`, e `start` e `undo` o chamam dentro de `runInTransaction`, antes do commit, com a linha relida depois de `markRunning` ou `markUndoRunning`. Uma falha ao criar a tarefa desfaz o lock; o fallback de `intProperty` continua valendo.
- Teste: `LifecycleTest.malformedExecutionPropertiesFallBackToTheDefaultsAndTheRunFinishes` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/LifecycleTest.java:515`).

### 4. Ordem de locks invertida em `updateCounts`

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:196`.
- Impacto: `updateCounts` atualizava `lum_cmig_Migration` com a subconsulta `GlobalLock.HELD_BY_RUN`, que lê `lum_cmig_MigLock` depois de travar a linha da migração. `GlobalLock.heartbeat` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/lock/GlobalLock.java:145`) e `finish` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:125`) travam na ordem inversa. A thread do item e o temporizador da mesma execução podiam então entrar em deadlock, a depender do plano do banco (por exemplo, no SQL Server em `READ COMMITTED` com locks). Se a vítima fosse `updateCounts`, a migração terminava em `STOPPED_WITH_ERROR` sem defeito real.
- Correção: `updateCounts` confirma o lock com `GlobalLock.confirm` antes de escrever, como `finish` faz, e atualiza a migração pela chave. A semântica de "não gravar sem o lock" não mudou.
- Teste: não há teste determinístico. O deadlock depende do plano de execução e de duas transações coincidirem em milissegundos. As suítes de ciclo de vida cobrem o resultado observável: contadores corretos e aborto sem escrita quando o lock é perdido.

### 5. Erro da operação mascarado pelo `close` da transação

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:156`.
- Impacto: `runInTransaction` fechava a transação num `finally`. Se o rollback de `TransactionHibernate#close` (`portal/src/java/lumis/portal/dao/hibernate/TransactionHibernate.java:320`) falhasse depois de um erro da operação, por exemplo com a conexão perdida, a exceção do `close` substituía a original. O chamador via o erro do `close`, e não a `MigrationApiException` ou a `SQLException` que explicava a falha.
- Correção: o método usa `try` com recurso, como `Transactions.inShortTransaction`. O erro da operação prevalece, e o do `close` fica como suprimido.
- Teste: não é testável sem injetar falha no `close` da plataforma. O caminho normal fica coberto por todas as suítes que chamam a API.

### 6. Javadoc da fachada diferente do código

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:47`, `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:431` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:455`.
- Impacto: o Javadoc da classe dizia que o REST chama a fachada por reflexão. Na verdade, a administração a chama diretamente e o REST usa `MigrationStatusQueries`. O texto também não avisava que `get`, `list` e `awaitTermination` não checam permissão. O de `stop` dizia que o método grava `STOPPED` e libera o lock, quando ele só registra o pedido, e o de `delete` falava em "migração parada", quando aceita toda migração que não esteja em execução.
- Correção: os três textos agora descrevem o comportamento real, e `delete` documenta o `LOCKED`.
- Teste: não se aplica (Javadoc).

### 7. Código morto e método público com pré-condição oculta

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dao/RepositoryMapDao.java:19` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/lock/GlobalLock.java:100`.
- Impacto: `RepositoryMapDao.listRepoMaps` não tinha chamador no módulo nem nos testes. `GlobalLock.databaseNow` era público, mas só funciona depois de `ensureLockRow`; sem a linha, lançava erro de cursor vazio.
- Correção: `listRepoMaps` foi removido, e `databaseNow` passou a privado, com a pré-condição no Javadoc.
- Teste: não se aplica (clareza, sem mudança de comportamento).

### 8. Ordem das verificações no laço do desfazer

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:261` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:273`.
- Impacto: `undoItems` checava a parada antes da perda do lock, ao contrário de `migrateItems` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java:193`). O resultado já era o mesmo, porque `finish` não grava sem o lock, mas o leitor precisava provar isso.
- Correção: os dois laços do desfazer seguem a ordem do laço de cópia.
- Teste: não se aplica (clareza, sem mudança de comportamento).

## Achados adiados

### 1. Migração órfã em `RUNNING` depois que outra migração assume o lock

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:488` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:102`.
- Impacto: se a JVM cai, ou se uma execução fica parada por mais de cinco minutos, outra migração pode assumir o lock vencido. A migração anterior continua em `RUNNING` para sempre: `delete` responde `LOCKED`, `stop` só marca o pedido e o REST a mostra como `INTERRUPTED`. Hoje a única saída é iniciar ou desfazer essa migração de novo quando o lock estiver livre e então pará-la.
- Pergunta que decide: quando `GlobalLock.acquire` assume o lock vencido de outra migração, essa migração deve passar a `STOPPED_WITH_ERROR` com `errorType` `INTERRUPTED`? Ou `stop` e `delete` devem aceitar uma migração em `RUNNING` cuja execução não segura mais o lock?

### 2. `lum_cmig_MigRepoMap` é gravada e nunca lida

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:280` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:85`.
- Impacto: `create` grava o par de repositórios, e `delete` o apaga. Desfazer e excluir, porém, recalculam os pares a partir da configuração atual das dependências da instância. Se o repositório de mídia do destino for trocado depois da migração, os clones do par antigo deixam de ser desfeitos ou limpos. O `douidefinition.xml` declara a fonte `repoMap`, mas nenhuma interface a usa, e o README descreve a tabela como o meio de reutilizar o par, papel que o código não cumpre.
- Pergunta que decide: a tabela deve virar a fonte dos pares de desfazer e excluir, preservando o par usado na migração, ou deve sair do modelo de dados e do README?

### 3. Nome acima de 255 caracteres

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:80` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/componentfiles/setup/databasedefinition.xml:7`.
- Impacto: `create` não limita o tamanho de `name`. Um nome maior que a coluna falha com erro de SQL genérico em vez de um código de validação.
- Pergunta que decide: o limite deve ir para o formulário de criação no `douidefinition.xml` ou virar um código novo de `MigrationApiException`, que exige uma string nova na administração?

### 4. `createdDate` usa o relógio da JVM

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dao/MigrationDao.java:66`.
- Impacto: `createdDate` vem do relógio do servidor de aplicação, enquanto `startDate`, `endDate` e `heartbeat` vêm de `CURRENT_TIMESTAMP` do banco. Num cluster, ou com o banco em outro fuso, a data de criação pode parecer posterior ao início. O efeito se limita à exibição e à ordenação de `list`.
- Pergunta que decide: `createdDate` deve passar a `CURRENT_TIMESTAMP`, como as demais datas? A mesma decisão vale para `MigrationStatusQueries.isStale`, que compara o heartbeat do banco com o relógio da JVM e pertence à área de consultas.

### 5. Dependência de "linhas encontradas" no MySQL

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/lock/GlobalLock.java:128` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dao/MigrationDao.java:195`.
- Impacto: `confirm` e `lockStoppedMigration` usam atualizações sem efeito (`set migrationId = migrationId`, `set id = id`) e esperam a contagem de linhas encontradas. O Connector/J faz isso por padrão, e a URL atual não muda esse comportamento. Com `useAffectedRows=true`, porém, as duas atualizações contariam zero linhas: todo item terminaria como lock perdido e todo `delete` receberia `LOCKED`. Oracle e SQL Server sempre contam as linhas encontradas.
- Pergunta que decide: basta documentar no README, em "Instalação", que a URL JDBC do MySQL não pode usar `useAffectedRows=true`, ou as duas travas devem mudar para uma forma que não dependa da contagem?

## Verificações executadas

Comandos rodados a partir da raiz do módulo, com o resultado obtido. Os rótulos `VAL-*` nomeiam as verificações desta rodada de revisão; quando o comando importa, ele aparece entre parênteses.

- Prova de regressão antes da correção: as correções de `src` foram guardadas em `git stash`, os testes novos ficaram aplicados, e `bash test/run-tests.sh PersistenceContractTest LifecycleTest` terminou com `Tests run: 15, Failures: 4`. As quatro falhas foram exatamente as previstas: `createdDate 2026-10-17T12:12:08` (achado 2), `expected INVALID_UPDATE_MODE for updateMode FOO` (achado 1), `startDate = 2026-10-17T12:13:01` (achado 2) e `NumberFormatException: For input string: "30s"` lançada por `start` (achado 3). Os outros 11 testes passaram.
- Depois da correção, todas as verificações terminaram com saída 0. `VAL-COMPILE` (`mvn clean test-compile`) compilou sem erro. `VAL-STYLE-CORE` verificou 26 arquivos sem violações, e `VAL-STRUCTURE-TESTS` terminou com `PASS structure: 0 error(s), 0 warning(s)`. As suítes de portal deram `VAL-PERSIST` com `OK (8 tests)`, `VAL-LIFECYCLE` com `OK (7 tests)` e `VAL-ADMIN` com `OK (2 tests)`. `VAL-REVIEW` validou este relatório no perfil `review`.
- `bash test/format.sh` sobre os arquivos Java alterados não mudou nenhum arquivo, e `python3 test/check_style.py` nos mesmos arquivos e nos testes alterados terminou sem violações.
