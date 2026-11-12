# Migração de conteúdo hierárquico

O serviço `br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration` é um clone de `contentmigration` que só migra instâncias de `lumis.service.hierarchicalcontent` e deixa o administrador do portal escolher quais conteúdos migrar. A cópia de cada conteúdo (versões, metadados, mídia, documento, categorização, widget), o controle de execução, o desfazer e a tela de acompanhamento seguem o original. Este documento descreve o que muda e as decisões que valem para este serviço.

## Diferenças em relação a `contentmigration`

| Ponto | `contentmigration` | `hierarchicalcontentmigration` |
| --- | --- | --- |
| Serviço migrado | Qualquer serviço de conteúdo, escolhido no formulário | Fixo em `lumis.service.hierarchicalcontent`; não há seletor |
| O que é migrado | Todos os conteúdos da origem | Os conteúdos marcados e todos os seus descendentes |
| Quem escolhe o escopo | Quem vê a origem e edita o destino | Só o administrador do portal (`MANAGE_PORTAL`) |
| Tabelas | `lum_cmig_Mig*` | `lum_cmig_HMig*`, mais `lum_cmig_HMigSelection` |
| REST de status | `/br/com/lumis/commons/contentmigrator/contentmigration/status`, mas o JS da lista chama outro prefixo (veja [Limitações e evolução](#limitações-e-evolução)) | `/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration/status`, chamado pela raiz do contexto |
| Fila de execução | Trava `lum_cmig_MigLock` | Trava própria, `lum_cmig_HMigLock`; a convivência com o original segue a regra da decisão D5 |

## Instalação

### Pré-requisitos

- Lumis Portal 18.1.1 e o módulo `br.com.lumis.commons.contentmigrator` publicado, como descrito no [README](../README.md).
- Duas instâncias distintas do serviço `lumis.service.hierarchicalcontent`, com dependências compatíveis, pelas mesmas regras do original (workflow, mídia, documento, categorização, tags e widgets).

### Implantar e instalar

1. Instale o componente `hierarchicalcontentmigration` pelo processo padrão do Portal. O [script de instalação](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/componentfiles/setup/component-install-script.xml) aplica o [databasedefinition.xml](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/componentfiles/setup/databasedefinition.xml) e cria as seis tabelas descritas em [Modelo de dados](#modelo-de-dados).
2. Crie uma instância do serviço **Migração de conteúdo hierárquico** em um canal administrativo.
3. Conceda as permissões da instância conforme a tabela a seguir.

### Permissões

A regra completa está em [MigrationPermissions.requirePortalAdministrator, linhas 19-37](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/security/MigrationPermissions.java#L19-L37), usada pela API e pelo REST. O usuário convidado é sempre recusado.

| Operação | Permissão exigida |
| --- | --- |
| Criar, **Alterar seleção**, **Excluir** e **Desfazer** | `MANAGE_PORTAL`. Criar exige também visualizar a origem e editar o conteúdo do destino. |
| `GET /contents` e `POST /selection` | `MANAGE_PORTAL`, checada antes de validar os parâmetros |
| Iniciar e **Parar** | Visualizar a instância de origem e editar o conteúdo do destino, como no original |
| `GET /status` e a lista | As migrações cujo par o usuário pode operar (visualizar a origem e editar o destino) |
| **Mapear widget** | Visualizar a origem e editar o repositório de widgets do destino, como no original |

Quem não é administrador recebe `FORBIDDEN` na API, com a mensagem traduzida `STR_MIGRATION_FORBIDDEN`, e **403** no REST.

### Propriedades do sistema

As propriedades são lidas de `System.getProperty`, documentadas em [ExecutionSettings.java](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/config/ExecutionSettings.java). Um valor que não é inteiro é ignorado com aviso no log, e vale o padrão.

| Propriedade | Padrão | Efeito |
| --- | --- | --- |
| `contentmigrator.hierarchicalcontentmigration.maxSelection` | 2000 | Teto de ids enviados a `/selection`, à criação e a **Alterar seleção**, contados antes de remover duplicados |
| `contentmigrator.hierarchicalcontentmigration.browserCacheSeconds` | 60 | Validade da árvore em memória do navegador, por instância e por nó do cluster; 0 desliga o cache |
| `contentmigrator.hierarchicalcontentmigration.browserCacheMaxNodes` | 200000 | Instância com mais conteúdos que isso não entra no cache; cada pedido lê do banco |
| `contentmigrator.hierarchicalcontentmigration.heartbeatSeconds` | 60 | Intervalo do heartbeat, de 1 a 60 s |
| `contentmigrator.hierarchicalcontentmigration.itemTimeLimitSeconds` | 1800 | Tempo de um item; acima dele o heartbeat não é renovado |

As propriedades `faultInjection.*` existem só para os testes e ficam desligadas por padrão.

### Desinstalar

Pare as migrações em execução e faça backup antes de desinstalar. O [script de desinstalação](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/componentfiles/setup/component-uninstall-script.xml) remove `lum_cmig_HMigSelection`, `lum_cmig_HMigLock`, `lum_cmig_HMigWidgetMap`, `lum_cmig_HMigRepoMap`, `lum_cmig_HMigItemMap` e `lum_cmig_HMigration`. Ele não apaga conteúdos nem arquivos do destino, mas remove as seleções e os mapas necessários para retomar ou desfazer migrações. As tabelas `lum_cmig_Mig*` do original não são tocadas.

## Uso da administração

Abra a interface **Administração** da instância. A lista mostra, além das colunas do original, a coluna **Marcas** (quantidade de marcas gravadas) e acompanha o status pelo REST a cada 60 segundos.

### Escolha dos conteúdos

No formulário **Nova migração** o campo **Conteúdos a migrar** é um navegador da instância de origem:

- Ele abre nos conteúdos sem pai. Um conteúdo com filhos tem um botão (`▸ N filhos`) que abre o nível dele. A trilha no topo leva de volta a cada nível; o primeiro item, **Conteúdos**, volta à raiz.
- Cada nível vem paginado (200 itens por página). **Carregar mais** traz a próxima página e o foco vai para o primeiro item novo.
- Todos os conteúdos com alguma versão aparecem, inclusive arquivados, rascunhos e ocultos. O banco não guarda o estado de workflow da versão, então um conteúdo **arquivado aparece como Sem versão publicada**, a mesma indicação de um rascunho nunca publicado. Os ocultos recebem a indicação **Oculto**.
- Marcar um conteúdo cobre os descendentes: dentro dele, os filhos aparecem marcados e desabilitados. Marcar um ancestral remove as marcas dos descendentes; um item com marca abaixo dele aparece como indeterminado.
- O resumo no pé mostra quantos conteúdos foram marcados, quantos serão migrados com os descendentes e quantos itens (um por idioma) serão copiados. As contagens vêm de `POST /selection` cerca de meio segundo depois da última mudança.

Ao criar, a API guarda em `lum_cmig_HMigSelection` só as marcas que nenhum ancestral marcado cobre. Os descendentes são recalculados a partir da origem a cada execução, e `totalCount` é recalculado no início de cada execução.

### Alterar seleção

O botão **Alterar seleção** da lista abre o mesmo navegador com as marcas atuais e regrava a seleção, sem apagar mapas nem histórico. Use-o para incluir ou retirar subárvores, em vez de excluir e recriar a migração.

- Só o administrador do portal altera a seleção, e só com a migração fora de **Em execução**. Com a migração em execução, a resposta é `LOCKED`.
- Uma migração interrompida (status **Em execução** com heartbeat parado) bloqueia a alteração até que alguém a inicie e a pare.
- Um conteúdo retirado da seleção continua no destino e não recebe mais atualizações. O **Desfazer** ainda o remove, porque o desfazer usa os mapas do par.

### Execução, exclusão e desfazer

- **Iniciar incremental** (`ADD_ONLY`) copia só os conteúdos sem cópia; **Iniciar atualização** (`UPDATE`) reescreve as cópias e move a cópia para baixo da cópia do novo pai quando o pai muda na origem.
- **Parar** vale depois do item em andamento; a execução seguinte continua sem duplicar itens.
- `migratedCount` conta só os itens dentro da seleção atual.
- Os avisos de cada item (pai não migrado, link para outro website, layout ausente) ficam no mapa do item e aparecem em **Avisos**.
- **Excluir** apaga a seleção e os mapas de conteúdo do par, mas mantém as cópias no destino. A confirmação recomenda **Alterar seleção**, porque uma migração recriada para o mesmo par não conhece as cópias anteriores e cria cópias novas; o desfazer da nova migração remove só as novas. Para limpar o destino, desfaça antes de excluir.
- **Desfazer** remove os conteúdos que a migração criou e que não têm referências, inclusive os que saíram da seleção; os referenciados ficam, com aviso. A confirmação descreve esse efeito.

## Serviço REST

Base: `{contexto}/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration`. Os recursos REST de módulo são montados na raiz do contexto pelo filtro REST do portal; o prefixo `lumis/api/rest` pertence a outro servlet e devolve 404 para eles. As chamadas precisam do cabeçalho `Origin` ou `Referer` do próprio portal, senão o filtro anti-CSRF as bloqueia.

| Método e caminho | Para que serve |
| --- | --- |
| `GET /contents?serviceInstanceId=<id>[&parentContentId=<id>][&offset=<n>][&limit=<n>]` | Filhos diretos do conteúdo; sem `parentContentId`, os conteúdos sem pai. `offset` padrão 0; `limit` padrão 200 e máximo 500. Devolve `breadcrumb`, `items` (`contentId`, `title`, `hasChildren`, `childCount`, `published`, `hidden`), `total`, `offset`, `limit` e `snapshotMillis`. |
| `POST /selection` com `{"serviceInstanceId": "...", "contentIds": ["..."]}` | Resolve uma seleção: `markedContentIds`, `paths` (ancestrais de cada marca, da raiz ao pai), `unknownContentIds`, `contentCount` e `localeCount`. Aceita até `maxSelection` ids e corpo de até 256 KB. |
| `GET /status` | Status das migrações que o usuário pode operar, como no original. |

Tetos e erros:

- `/contents` com `offset` negativo ou `limit` fora de 1 a 500 responde **400** ([HierarchyBrowser.isValidPage, linhas 49-53](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/hierarchy/HierarchyBrowser.java#L49-L53)).
- `/selection` acima de `maxSelection` ids ou de 256 KB responde **400** com `{"error": "SELECTION_TOO_LARGE"}` ([HierarchicalContentBrowserResource.resolveSelectionForCurrentUser, linhas 105-140](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/rest/HierarchicalContentBrowserResource.java#L105-L140)).
- Pedido inválido ou instância fora do serviço hierárquico: **400**. Instância ou conteúdo inexistente: **404**. Quem não é administrador: **403**, antes de qualquer validação ([HierarchicalContentBrowserResource.runAsAdministrator, linhas 147-176](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/rest/HierarchicalContentBrowserResource.java#L147-L176)).

## API

`MigrationApi.create` recebe `name`, `originServiceInstanceId`, `destServiceInstanceId`, `selectedContentIds` (ids separados por vírgula) e, opcionalmente, `updateMode`. `MigrationApi.updateSelection` recebe `migrationId` e `selectedContentIds`. Os dois aplicam o teto `maxSelection` antes de separar os ids ([MigrationApi.readContentIds, linhas 182-210](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/api/MigrationApi.java#L182-L210)).

| Código | Quando |
| --- | --- |
| `NOT_HIERARCHICAL` | A instância não é do serviço hierárquico |
| `NO_SELECTION` | Nenhum conteúdo marcado, ou a hierarquia selecionada ficou vazia no início |
| `INVALID_SELECTION` | Um id não está na origem |
| `SELECTION_TOO_LARGE` | Mais ids que `maxSelection` |
| `FORBIDDEN` | Usuário sem a permissão da operação |
| `LOCKED` | Alteração, exclusão ou desfazer com a migração em execução, ou início com outra execução ativa |
| `CONTENTMIGRATION_CONFLICT` | Conflito com o `contentmigration` original (decisão D5) |

## Modelo de dados

| Tabela | Finalidade |
| --- | --- |
| `lum_cmig_HMigration` | Uma migração por par de instâncias; modo, operação, status, contagens, erro, autor e o conteúdo bloqueante de widget. |
| `lum_cmig_HMigSelection` | As marcas mínimas de cada migração, únicas por (`migrationId`, `contentId`). |
| `lum_cmig_HMigItemMap` | Liga cada `ContentLocale` de origem ao destino, por par, fonte e locale; guarda os avisos do item e se o destino foi criado pelo módulo. |
| `lum_cmig_HMigRepoMap` | Pares de repositórios registrados na criação. |
| `lum_cmig_HMigWidgetMap` | Widget de origem e o widget escolhido no repositório do destino. |
| `lum_cmig_HMigLock` | Trava global (linha `global`) e heartbeat da execução ativa deste serviço. |

O serviço lê, sem gravar, `lum_cmig_Migration` e `lum_cmig_MigLock` do original para a regra de convivência.

## Decisões de arquitetura

### D1. Links para página, canal, conteúdo e layout

Os tipos de item 2 (página), 5 (canal) e 6 (conteúdo) são copiados por [LinkFieldPolicy.resolve, linhas 48-105](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/copy/LinkFieldPolicy.java#L48-L105):

- Página e canal mantêm o id, porque são objetos globais do portal. Alvo inexistente vira vazio com aviso; alvo de outro website fica com aviso, porque o menu do destino não destaca o item.
- `linkContentId` que aponta para a origem recebe o id da cópia. Se o alvo ainda não foi copiado (referência para frente), fica o id da origem com aviso, e **Iniciar atualização** remapeia. Conteúdo de outra instância é mantido.
- `pageLayoutId` só fica se a chave existir em `pageLayouts` do destino; senão vira vazio com aviso e vale o detalhe padrão.

### D2. Pai quando versões e idiomas divergem

O pai de referência é o do nó da árvore: locale de referência e versão ativa, ou a publicada quando não há ativa. Ele define o fechamento da seleção. Na cópia, cada versão usa o próprio pai se ele estiver mapeado; senão, o pai de referência, com aviso; senão, fica sem pai, com aviso ([ContentCopier.resolveParentContentId, linhas 273-293](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/copy/ContentCopier.java#L273-L293)). Um conteúdo que está sob um item marcado só em outro idioma não entra na seleção.

### D3. Ordem de cópia

Primeiro o locale de referência de cada conteúdo, em ordem topológica sobre a união do pai ativo e do publicado; depois os demais locales, por data de criação ([HierarchyTree.ancestorsFirst, linhas 197-248](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/hierarchy/HierarchyTree.java#L197-L248)). Nós presos em ciclo vão para o fim, em ordem estável. O locale de referência é o padrão do conteúdo, quando tem versão, ou o mais antigo, e é calculado uma vez em [HierarchyQueries.loadTree, linhas 27-64](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/hierarchy/HierarchyQueries.java#L27-L64).

### D4. Seleção editável e par único

O par continua único. **Alterar seleção** regrava as marcas na transação que trava a linha da migração ([MigrationApi.updateSelection, linhas 139-160](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/api/MigrationApi.java#L139-L160), com [MigrationDao.lockStoppedMigration, linhas 169-183](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/dao/MigrationDao.java#L169-L183)), o que a serializa com início, exclusão e desfazer. As validações são as da criação.

### D5. Convivência com `contentmigration`

[MigrationApi.hasContentMigrationConflict, linhas 166-173](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/api/MigrationApi.java#L166-L173) aplica a regra:

- Criar e iniciar recusam com `CONTENTMIGRATION_CONFLICT` quando `lum_cmig_Migration` já tem o mesmo par ([ContentMigrationCoexistenceDao.hasPair, linhas 23-42](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/dao/ContentMigrationCoexistenceDao.java#L23-L42)). Os dois serviços têm mapas separados, e o mesmo par duplicaria conteúdos.
- Iniciar também recusa quando `lum_cmig_MigLock` tem execução recente gravando no mesmo destino ([ContentMigrationCoexistenceDao.hasActiveDestination, linhas 48-69](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/dao/ContentMigrationCoexistenceDao.java#L48-L69)). A checagem roda na transação que toma a trava, e a recusa desfaz a trava.
- Um destino comum com o original parado é permitido.
- Se as tabelas do original não existirem, a checagem é ignorada.

O `contentmigration` não foi alterado e não conhece esta regra. Não use o original em instâncias hierárquicas que já têm migração hierárquica: ele pode iniciar logo depois da checagem deste serviço, e essa janela é aceita.

### D6. Leitura da árvore em escala

`lum_HierarchicalContent` não tem índice em `parentContentId`, então qualquer leitura por nível varre a instância. O navegador mantém a árvore em memória com validade de `browserCacheSeconds`, no máximo três instâncias por nó do cluster e carga única por instância (`HierarchyBrowser.Cache`). Instâncias acima de `browserCacheMaxNodes` são lidas do banco a cada pedido. Os níveis são paginados ([HierarchyBrowser.browse, linhas 56-100](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/hierarchy/HierarchyBrowser.java#L56-L100)). Criar, iniciar e a descoberta da execução ([ItemProcessor.discover, linhas 105-124](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/copy/ItemProcessor.java#L105-L124)) sempre leem do banco.

### D7. Regra de acesso

Escolher o escopo, excluir e desfazer exigem `MANAGE_PORTAL`, porque o desfazer apaga cópias e clones de repositório e a exclusão apaga mapas que só um administrador recria. Iniciar e parar seguem a regra do original ([MigrationApi.start, linhas 516-552](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/api/MigrationApi.java#L516-L552)). A tabela completa está em [Permissões](#permissões).

### D8. Heartbeat pelo relógio do banco

O status mede o atraso do heartbeat com o "agora" do banco ([GlobalLock.databaseNow, linhas 110-130](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/lock/GlobalLock.java#L110-L130)), o mesmo relógio que grava o heartbeat e que a trava usa. Uma execução é considerada interrompida após 5 minutos sem heartbeat ([MigrationStatusQueries.listStatuses, linhas 37-80](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/query/MigrationStatusQueries.java#L37-L80)).

### D9. Contrato do navegador

O script do navegador, em [douidefinition.xml](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/douidefinition.xml), é a fonte das marcas: guarda cada marca com o caminho da trilha, grava os ids no campo oculto a cada mudança e calcula coberto e indeterminado no cliente. `/selection` só restaura caminhos e conta, com debounce e número de sequência; respostas antigas são descartadas e nunca mudam as marcas. O servidor revalida e minimiza a seleção na criação e em **Alterar seleção**.

## Organização do código

- `hierarchy/HierarchyTree`: a hierarquia em memória, com filhos, trilha, descendentes, seleção mínima, ordem pais-primeiro e nós em ciclo promovidos a raiz.
- `hierarchy/HierarchyQueries`, `hierarchy/MigrationSelection`, `hierarchy/HierarchyBrowser`: leitura da árvore, expansão da seleção, cache e respostas do navegador.
- `copy/LinkFieldPolicy`: a política de links da decisão D1.
- `dao/SelectionDao` e `dao/ContentMigrationCoexistenceDao`: as marcas e a leitura das tabelas do original.
- `security/MigrationPermissions`: a regra de acesso.
- `rest/HierarchicalContentBrowserResource`: `/contents` e `/selection`.
- Em `douidefinition.xml`, as interfaces `addMigration` e `editSelection` (campo `selectedContentIds`) e `migrationWarnings`.

## Testes

Execute com `bash test/run-hier-tests.sh [Classe...]`; sem argumentos, roda todas as classes do serviço e termina com `NoLeftoverDataTest`. O script nunca inclui testes do `contentmigration`. O smoke da interface roda com `bash test/ui-smoke.sh` (Playwright, [test/ui/smoke.js](../test/ui/smoke.js)).

| Área | Classes |
| --- | --- |
| Regras da árvore e cache, sem portal | `HierarchyTreeTest` |
| Seleção, ciclo de vida e alteração da seleção | `SelectionMigrationTest`, `MigrationLifecycleTest`, `EditSelectionTest` |
| Pai por versão e idioma, avisos e links | `MultiLocaleMigrationTest`, `MigrationWarningsTest`, `ContentTypesMigrationTest` |
| Dependências | `MediaDocumentMigrationTest`, `CategorizationTagsMigrationTest`, `WidgetWorkflowMigrationTest` |
| Acesso, REST e administração | `MigrationAccessTest`, `HierarchyBrowserRestTest`, `StatusRestTest`, `AdminFormValidationTest`, `MigrationProcessActionHandlerTest` |
| Contratos e convivência | `CoexistenceGuardTest`, `PersistenceContractTest`, `SpiRulesTest`, `MigrationDtoTest`, `HarnessSmokeTest` |

### Áreas de teste do original não portadas

Os cenários de notícias e de `lumis.service.userpost` não se aplicam a este serviço. Os cenários a seguir existem no original e não têm equivalente aqui; o código correspondente foi clonado sem mudança de comportamento:

- Os quatro testes `*SpikeTest` (metadados, clone de documento, clone de mídia e link de widget).
- Do `LifecycleTest`: falha injetada por `failAtItem` com retomada incremental, retomada de execução com heartbeat parado, perda da trava durante o item e propriedades de execução malformadas.
- Do `SimpleMigrationTest`: slug duplicado com sufixo, locale não aceito pelo canal de destino, principais e alvos de publicação e fonte sem campo de idioma.
- Do `DeleteUndoMigrationTest`: mapas de repositório compartilhados mantidos até a última migração e desfazer que para no filho e pode ser repetido.
- Do `WorkflowMigrationTest`: usuário sem administração com papel só do estado inicial e fonte sem workflow em instância com workflow.
- Do `WidgetMappingAdminTest`: a ordem da checagem de permissão na consulta e na ação de mapear widget, antes de revelar o estado da migração.

## Limitações e evolução

- **Prefixo REST do original:** o JS da lista do `contentmigration` chama `lumis/api/rest/br/com/lumis/commons/contentmigrator/contentmigration/status` ([douidefinition.xml, linha 315](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/douidefinition.xml#L315)), prefixo que devolve 404; o acompanhamento automático do original não atualiza a lista. É um problema conhecido e não corrigido, porque o original fica intacto. Este serviço usa a raiz do contexto ([douidefinition.xml, linha 362](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/douidefinition.xml#L362)). **Evolução:** corrigir o caminho no original em uma entrega própria.
- **Convivência:** a recusa de D5 só existe neste serviço. **Evolução:** levar a mesma checagem ao original, se ele puder mudar.
- **Escala:** o cache é por nó do cluster e só serve o REST. Uma instância acima de `browserCacheMaxNodes` lê a instância inteira a cada pedido. **Evolução:** sugerir ao DBA um índice em `lum_HierarchicalContent(parentContentId)`; o módulo não altera tabelas da plataforma.
- **Tetos:** até `maxSelection` marcas (2.000 por padrão), 256 KB no corpo de `/selection` e 500 itens por página. As marcas são as mínimas, escolhidas à mão, então o teto vale para o que o usuário marca, não para o total migrado.
- **Arquivados:** a interface não distingue arquivado de rascunho; os dois aparecem como **Sem versão publicada**. Conteúdo sem nenhuma versão não aparece, porque não há o que copiar.
- **Referência para frente no tipo 6:** fica o id da origem com aviso até uma execução de atualização.
- **Idiomas:** um locale não aceito pelo canal de destino bloqueia a execução no primeiro item desse locale, como no original. Um conteúdo sob um item marcado só em outro idioma não migra (decisão D2).
- **Migração interrompida:** **Alterar seleção** e **Excluir** ficam bloqueados até que alguém inicie e pare a migração.
- **Oracle e SQL Server:** as consultas são portáveis, mas só foram executadas no MySQL. A busca de tabela do original em maiúsculas e minúsculas não foi validada nesses bancos. **Evolução:** incluir esses bancos na matriz de testes.
- **Remover ao desmarcar:** desmarcar não remove a cópia do destino. **Evolução:** oferecer um desfazer parcial por subárvore.
