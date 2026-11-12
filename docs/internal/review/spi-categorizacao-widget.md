# Review da SPI de dependências, da categorização e do widget: dependency, dependency/categorization e dependency/widget

Este relatório registra o review da SPI de dependências e dos handlers de categorização e de widget, do serviço `br.com.lumis.commons.contentmigrator.service.contentmigration` (raiz `R`). As referências `arquivo:linha` do módulo apontam para o código depois das correções. As da plataforma Lumis são relativas a `Lumis_PortalJava` e foram conferidas no fonte, lido em ISO-8859-1.

## Escopo revisado

As classes foram lidas inteiras antes do julgamento, com os critérios de PAT001: correção, concorrência, transação por item, SQL portável para MySQL, Oracle e SQL Server, permissões, tratamento de erro, liberação de recursos, clareza e Javadoc fiel ao código.

- `R.dependency`: `DependencyHandler`, `DependencyContext`, `DependencyHandlerRegistry`, `RepositoryCloner` e `FileCloner`.
- `R.dependency.categorization`: `CategorizationDependencyHandler`.
- `R.dependency.widget`: `WidgetDependencyHandler`.

Os handlers de mídia e de documento ficaram fora, porque já têm review próprio. Para julgar os fluxos, também foram lidos, sem alteração, os pontos do motor que chamam a SPI: os `precheck` em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:361`, o `resolve` dos campos em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:252`, o `rewriteHtml` de todos os handlers em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:331`, o `resolve` das associações de categoria em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:434` e o desfazer de clones em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:613`.

### Contrato da SPI

A ordem descrita no Javadoc de `DependencyHandler` não era a do motor, e foi corrigida (achado 5). O motor chama os `precheck` de todos os handlers antes de gravar (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:212`). Depois, ao montar os dados de cada versão, percorre os campos na ordem da fonte: chama o `resolve` do primeiro handler que trata o campo e, num valor html, o `rewriteHtml` de todos os handlers, na ordem do registro (mídia, documento, widget e categorização). Antes do `rewriteHtml`, `HtmlDataType#prepareValueForDataCloning` já remonta o JSON das áreas (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1454`). O `resolve` de categorização só é chamado depois de a versão ser gravada, uma vez por associação `categorizationTerm`, com o tipo da associação no lugar do id do campo.

`RunStatusUpdater#findBlockedException` percorre as causas da exceção (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/RunStatusUpdater.java:154`), e por isso o `MigrationBlockedException` lançado dentro do `rewriteHtml` de widget, embrulhado em `PortalException`, ainda vira bloqueio. Esse bloqueio é defensivo: o `precheck` lê as mesmas versões e os mesmos campos html na mesma transação, então um widget sem mapeamento para o item antes de qualquer gravação.

### Clones, mapa único e transação

`RepositoryCloner` resolve cada item pelo mapa `lum_cmig_MigItemMap`, chaveado pelo par de repositórios. No modo `ADD_ONLY`, um clone mapeado é reaproveitado sem alteração; no modo de atualização, é atualizado. Um mapa cujo destino foi apagado é reaproveitado para um clone novo, e uma pasta ou termo de mesmo título sob o mesmo pai é reaproveitado e mapeado como não criado pela migração. Todas as consultas usam a conexão da transação do item, e um erro desfaz clones e mapas juntos.

A chave única `LUM_UN_CMIG_MIGITEMMAP` só é violada se duas gravações do mesmo item de repositório concorrerem, o que o lock global impede entre execuções. `isUniqueMapConflict` reconhece o nome da chave na mensagem da exceção, das causas e das exceções SQL encadeadas, e `ItemProcessor#process` repete o item uma vez numa transação nova (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:164`). A repetição é coberta pela injeção de falha de `CategorizationMigrationTest`. O método foi simplificado sem mudar o comportamento (achado 6).

Os handlers são instâncias únicas do registro e não guardam estado entre chamadas; os adaptadores de `RepositoryCloner` são criados por chamada. `FileCloner` é aberto por item e fechado depois do commit ou do rollback, como o review dos handlers de mídia e documento registrou. As escritas nos repositórios passam pelas fontes nativas, com as permissões delas.

### HTML com widgets embutidos

O embed de widget é um `div` vazio com o id da instância de `HtmlFieldWidgetInstance`. A expressão `EMBED` do handler (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/widget/WidgetDependencyHandler.java:70`) é igual a `WIDGET_ENCODE_PATTERN` da plataforma (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:115`). Ela exige a grafia `<div`, `id` entre aspas duplas e um fechamento `></div>` sem conteúdo interno; captura exatamente os primeiros 32 caracteres `\w` depois do prefixo do id. Um id menor não casa; um maior casa com os primeiros 32 caracteres capturados, mas o restante não forma um id canônico e a plataforma não encontra a instância. A plataforma usa esse padrão para renderizar (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:331`), clonar e ligar as instâncias aos itens. Variações como `<DIV>`, `id` entre aspas simples ou conteúdo dentro do `div` ficam fora do formato que a própria plataforma processa e o handler preserva como texto. No atributo `data-widget-interfaceinstanceid`, a plataforma usa Jsoup (`PagePersonalizationUtil#cloneWidgetsIfNeeded`, linhas 811–900), que aceita aspas simples ou duplas; o handler agora também substitui ambas as formas. Nada do conteúdo ao redor dos embeds é alterado, como o teste confere.

O ciclo de `HtmlFieldWidgetInstance` foi conferido no fonte:

- `HtmlDataType#preAdd` e `HtmlDataType#preUpdate` chamam `PagePersonalizationUtil#cloneWidgetsIfNeeded` (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1098` e `doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:981`). O método clona a instância ligada a item de outro conteúdo (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:860`), copia as propriedades e as preferências compartilhadas e recusa o embed de uma instância que não existe, com `STR_WIDGET_INSTANCE_DONT_EXISTS` (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:843`).
- `HtmlDataType#postAdd` chama `PagePersonalizationUtil#processWidgetInstaceInHtmlAdded` (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:1002`), que liga cada instância embutida ao item gravado. Numa fonte com versionamento, `ContentTableSource#doUpdateData` grava uma linha nova e dispara os eventos de inclusão (`content/src/java/lumis/content/table/ContentTableSource.java:866`), e a versão anterior mantém as instâncias dela. Sem versionamento, o `updateData` dispara `PagePersonalizationUtil#processWidgetInstaceInHtmlUpdate`, e `PagePersonalizationUtil#removeDeletedWidgets` apaga a instância que o valor novo deixou de embutir, mesmo que outro item ainda a use (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:647`).
- O construtor `WidgetInstance(String)` cria a instância de interface derivada do widget na transação corrente e falha se o conteúdo do widget não existe (`pagepersonalization/src/java/lumis/service/pagepersonalization/bean/WidgetInstance.java:107`). O relógio `DeleteUnusedHtmlFieldWidgetInstance` apaga, depois de duas horas, as instâncias que nenhum item ligou, como a de um HTML gravado sem passar pelos eventos da fonte (`pagepersonalization/src/java/lumis/service/pagepersonalization/clock/DeleteUnusedHtmlFieldWidgetInstance.java:23`). Esse relógio, `removeDeletedWidgets` com uma instância usada por mais de um item e a exclusão do widget, que apaga todas as instâncias dele (`pagepersonalization/src/java/lumis/service/pagepersonalization/widgetmanipulationaction/PagePersonalizationManager.java:81`), são os caminhos pelos quais um conteúdo fica com embed órfão.

Com repositórios diferentes, o handler cria na transação do item uma instância do widget mapeado para cada embed, como `cloneWidgetsIfNeeded` faz, e copia as propriedades e as preferências do embed de origem quando os dois widgets têm a mesma interface, como `PagePersonalizationUtil#copyProperties` (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:936`). A instância nova não tem item, então `cloneWidgetsIfNeeded` não a clona de novo, e `processWidgetInstaceInHtmlAdded` a liga ao item de destino. Com repositório compartilhado, ou sem repositório de um dos lados, o handler mantém os embeds e o próprio `preAdd` clona as instâncias. Um rollback do item desfaz as instâncias criadas, porque elas e as instâncias de interface são gravadas na mesma transação.

### Categorização e tags

Os termos são resolvidos pelo caminho: o pai é resolvido antes do filho, e um termo de mesmo nome sob o mesmo pai no destino é reaproveitado. A fonte `categorizationTerm` é multilíngue (`content/src/java/lumis/service/categorization/douidefinition.xml:20`). O nome e a linha do termo são lidos no idioma do conteúdo migrado, ou no idioma padrão do termo quando ele não tem esse, como faz `CategorizationUtil#getBestTermName` (`content/src/java/lumis/service/categorization/CategorizationUtil.java:178`). O mapa guarda uma linha por idioma do termo.

As tags não têm handler na SPI. `ContentCopier` copia as tags pelo nome (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:479`), e `ContentLocale#addTag` resolve cada nome no repositório de tags do destino, criando a tag quando ela não existe (`content/src/java/lumis/content/core/ContentLocale.java:1004`). `DependencyRules` exige repositório de tags no destino quando a origem tem um (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/validation/DependencyRules.java:58`).

## Achados corrigidos

### 1. Embed órfão derrubava o item com repositório de widgets compartilhado

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/widget/WidgetDependencyHandler.java:211`.
- Impacto: com repositórios diferentes, o handler já removia, com aviso, o embed de uma instância ou de um widget que não existe mais. Com o mesmo repositório nos dois lados, ou sem repositório de um dos lados, `rewriteHtml` devolvia o HTML sem olhar os embeds. O `preAdd` do destino recusava então o embed órfão com `STR_WIDGET_INSTANCE_DONT_EXISTS`, ou falhava no construtor de `WidgetInstance` quando o widget tinha sido apagado, e a migração parava no item com erro da plataforma. O mesmo conteúdo migrava entre repositórios diferentes e falhava entre instâncias do mesmo repositório.
- Correção: os embeds de instância ou de widget inexistente são removidos com o mesmo aviso em todos os casos; os demais ficam como estão quando não há mapeamento a aplicar. O HTML sem nenhum embed volta sem passar pela remontagem das áreas.
- Teste: `WidgetMigrationTest.removesOrphanEmbedsAndKeepsTheOthersWhenTheRepositoryIsShared` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/WidgetMigrationTest.java:187`). O teste grava o conteúdo com dois embeds, apaga uma das instâncias por `WidgetInstance#remove`, como `removeDeletedWidgets` e o relógio fazem, e confere que o embed órfão saiu, que o outro foi clonado pelo `preAdd` nativo e ligado ao item de destino e que o aviso ficou no mapa do item.

### 2. Origem sem repositório de categorização derrubava o item

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:124`.
- Impacto: `DependencyRules` permite origem sem repositório de categorização, e as associações a termos continuam na versão quando a dependência é desfeita depois de categorizar. O `precheck` tratava o repositório nulo, mas o `resolve` chamava `getOriginRepositoryInstanceId().equals(...)`: `NullPointerException` na cópia das associações, e o item parava.
- Correção: a comparação parte do repositório do termo, que nunca é nulo. Um termo de repositório diferente do da origem, inclusive quando a origem não tem repositório, é mantido com o aviso que o handler já registrava para termos de outro repositório.
- Teste: `CategorizationMigrationTest.keepsTermsWhenTheOriginHasNoCategorizationRepository` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/CategorizationMigrationTest.java:231`).

### 3. Cada idioma de um termo virava um termo diferente no destino

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:455` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:517`.
- Impacto: `RepositoryCloner` passa a `createFolder` o conteúdo de destino já mapeado para outro idioma do mesmo termo (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/RepositoryCloner.java:148`), mas o adaptador de termos ignorava esse id. Ao migrar a versão em inglês de uma notícia cujo termo já tinha sido criado em português, o handler criava outro termo, só em inglês e sem idioma padrão, com pai também duplicado. As duas versões da notícia ficavam associadas a termos diferentes, e um filtro pelo termo de destino só achava uma delas.
- Correção: no `addData`, o campo de metadado `ContentId` recebe o conteúdo já mapeado, e o idioma novo entra no mesmo termo, como `ContentCopier` faz com os idiomas de um conteúdo.
- Teste: `CategorizationMigrationTest.addsEachTermLocaleToTheSameDestinationTerm` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/CategorizationMigrationTest.java:182`), com raiz e filho em português e inglês e a notícia nos dois idiomas.

### 4. Consulta de referência do desfazer sem `from`

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:143`.
- Impacto: `isCloneReferenced` usava `select case when exists (...) then 1 else 0 end` sem `from`. O Oracle anterior à versão 23ai recusa essa forma (ORA-00923), e no Oracle todo desfazer de termo criado pela migração terminava com erro de SQL. O review dos handlers de mídia e documento corrigiu o mesmo padrão nas classes dele.
- Correção: a consulta conta a linha de `lum_ContentLocale` do clone com as condições `exists` correlacionadas, a forma adotada nos handlers de mídia e documento, aceita pelos três bancos.
- Teste: a falha no Oracle não é testável no ambiente local, que só tem MySQL. O README registra que Oracle e SQL Server não estão na matriz de integração (`README.md:121`). No MySQL, a consulta nova é exercida pelo handler registrado, com respostas verdadeira e falsa, em `CategorizationMigrationTest` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/CategorizationMigrationTest.java:175`).

### 5. Javadoc da SPI descrevia outra ordem de chamada

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/DependencyHandler.java:13`.
- Impacto: o Javadoc dizia que o motor chamava todos os `rewriteHtml` e depois todos os `resolve`, e listava só mídia, documento e widget. Um handler novo escrito pelo contrato contaria com uma ordem que não existe.
- Correção: o Javadoc descreve a ordem real, a resolução das associações de categoria depois da gravação, o `null` que omite a referência, o bloqueio que `rewriteHtml` pode lançar e a exceção de mesmo repositório para embeds órfãos. `WidgetDependencyHandler` e `CategorizationDependencyHandler` ganharam Javadoc de classe com o ciclo da plataforma e as regras de cada um.

### 6. Clareza

- `WidgetDependencyHandler#precheck` cria a fonte da origem uma vez por item, e não uma vez por versão (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/widget/WidgetDependencyHandler.java:98`). Os comentários depois do fecha-chaves passaram para antes do bloco, e `copyEmbedConfigurationIfSameInterface` usa a sessão do contexto e um só gerenciador (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/widget/WidgetDependencyHandler.java:276`).
- `CategorizationDependencyHandler` trocou os nomes qualificados de `ISourceField`, `Objects` e `LinkedHashMap` por imports, deixou de concatenar dois literais no `delete` de `deleteClone` e explica que o item do mapa de termo é o id do conteúdo (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:162`).
- `RepositoryCloner#isUniqueMapConflict` concentra a comparação da mensagem em `mentionsUniqueMapKey` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/RepositoryCloner.java:316`), e `Locale` virou import.

### 7. Desfazer de um par de repositórios removia mapas de outro par

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:166` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:200`.
- Impacto: `deleteClone` filtrava pelo destino, locale, source e tipo, mas ignorava `originServiceInstanceId`. Dois repositórios de origem podem reutilizar o mesmo termo do destino. Ao desfazer um deles, a consulta podia tomar como seu um mapa criado pelo outro e apagar o termo ainda usado por esse segundo par; o `delete` também removia os mapas de ambos. `MigrationUndoer#undoRepositoryItem` fornece ao handler os ids do par que está sendo desfeito (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:612` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:620`).
- Correção: as consultas de leitura e remoção agora limitam pelo par origem/destino. A leitura acumula `destCreated` nas linhas daquele par para não depender da ordem das linhas quando vários mapas apontam para o mesmo locale.
- Nota de 2026-10-18: `undoRepositoryItem` passou a `(session, runId, migration, item)` e monta um `MigrationScope`; `undoExistingRepositoryClone(scope, item, handler)` cria o contexto com `new DependencyContext(scope, item.getRepositoryPair(), null)`. Os ids do par continuam vindo do `RepositoryPair` do item.
- Teste: `CategorizationMigrationTest.deleteCloneDoesNotDeleteAnotherOriginRepositoryMapOrTerm` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/CategorizationMigrationTest.java:272`) semeia dois mapas para o mesmo termo de destino: um criado por outro repositório e o mapa reutilizado do par que está sendo desfeito. Verifica que o mapa local sai, enquanto o termo e o mapa externo permanecem.

### 8. Aspas simples no atributo de interface geravam atributo duplicado

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/widget/WidgetDependencyHandler.java:74` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/widget/WidgetDependencyHandler.java:261`.
- Impacto: o handler reconhecia apenas `data-widget-interfaceinstanceid` entre aspas duplas. Se o embed válido usasse aspas simples, `rewriteHtml` inseria um segundo atributo em vez de trocar o id da interface. O ciclo nativo aceita essa marcação: `PagePersonalizationUtil#cloneWidgetsIfNeeded` analisa o fragmento com Jsoup e grava o atributo atualizado (`pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:811` e `pagepersonalization/src/java/lumis/service/pagepersonalization/PagePersonalizationUtil.java:900`).
- Correção: a expressão agora reconhece nome do atributo sem distinção de caixa, espaços ao redor de `=`, e aspas simples ou duplas; a substituição mantém um único atributo em aspas duplas.
- Teste: `WidgetMigrationTest.blocksWithoutMappingThenResumesAndRewritesEveryEmbed` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/WidgetMigrationTest.java:104`) usa aspas simples em um embed de origem e confere que cada embed de destino tem apenas um atributo de interface.

## Achados adiados

### 1. Termo sem versão publicada

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:95` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/categorization/CategorizationDependencyHandler.java:338`.
- Situação: `CategorizationUtil#getTermPathContentIds` só acha o termo pela versão publicada (`content/src/java/lumis/service/categorization/CategorizationUtil.java:83`). Para um termo sem versão publicada em nenhum idioma, o `precheck` registra que o termo foi omitido, mas o `resolve` lê o nome pelo mesmo método, recebe `PortalObjectNotFoundException` e o item para. A fonte nativa grava o termo sem publicá-lo, como a fixture dos testes mostra ao publicar cada termo que cria.
- Pergunta: um termo sem versão publicada deve ser migrado a partir da versão ativa, omitido com aviso, como o `precheck` anuncia, ou bloquear o item?

### 2. Dependência removida do destino depois de criar a migração

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/api/MigrationApi.java:247`.
- Situação: `DependencyRules` só é conferido na criação da migração. Se a dependência de categorização do destino for desfeita depois, `CategorizationDependencyHandler` procura e cria termos num repositório nulo, e o item para com um erro da plataforma que não indica a causa. O widget mantém os embeds nesse caso e não falha.
- Pergunta: o início de cada execução deve conferir de novo as regras de criação? A mudança fica em `MigrationApi`, cujo contrato este review não altera.

## Verificações executadas

Comandos rodados a partir da raiz do módulo, com o resultado obtido. Os rótulos `VAL-*` nomeiam as verificações desta rodada de revisão; quando o comando importa, ele aparece entre parênteses.

- `mvn -B -q -f pom.xml clean test-compile` (`VAL-COMPILE`): passou.
- `python3 test/check_style.py --exclude src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media --exclude src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency` (`VAL-STYLE-DEP-CORE`): passou; 9 arquivos, nenhuma violação.
- `bash test/run-tests.sh SpiRulesTest` (`VAL-SPI`): passou, 7 testes.
- `bash test/run-tests.sh CategorizationMigrationTest` (`VAL-STAGE2-CATEGORY`): passou, 5 testes.
- `bash test/run-tests.sh WidgetMigrationTest` (`VAL-STAGE2-WIDGET`): passou, 2 testes.
- `python3 test/check_structure.py --strict-tests` (`VAL-STRUCTURE-TESTS`): passou; 0 erros e 0 avisos.
- `python3 test/check_architecture.py --profile review docs/internal/review` (`VAL-REVIEW`): passou com os achados 7 e 8 incluídos.
