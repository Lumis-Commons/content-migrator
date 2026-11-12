# Review dos handlers de mídia e documento: dependency/media e dependency/document

Este relatório registra o review de `MediaDependencyHandler` e `DocumentDependencyHandler`, do serviço `br.com.lumis.commons.contentmigrator.service.contentmigration` (raiz `R`). As referências `arquivo:linha` do módulo apontam para o código depois das correções. As da plataforma Lumis são relativas a `Lumis_PortalJava` e foram conferidas no fonte, lido em ISO-8859-1.

## Escopo revisado

As duas classes foram lidas inteiras antes do julgamento, com os critérios de PAT001: correção, concorrência, transação por item, SQL portável para MySQL, Oracle e SQL Server, permissões, tratamento de erro, liberação de recursos, clareza e Javadoc fiel ao código.

- `R.dependency.media`: `MediaDependencyHandler`.
- `R.dependency.document`: `DocumentDependencyHandler`.

As duas classes têm estrutura gêmea. Cada defeito achado numa foi conferido na outra, e a tabela abaixo resume onde ele existia.

| Achado corrigido | Mídia | Documento |
| --- | --- | --- |
| 1. Link de download relativo com prefixo `null` | existia | não existia |
| 2. Origem sem repositório do tipo derrubava o item | existia | existia |
| 3. Pasta homônima escolhida pela ordem do banco | existia | não existia |
| 4. Consulta de referência sem `from` | existia | existia |
| 5. Link absoluto movido de website com o arquivo da origem | não se aplica | existia |
| 6. Caminho público com escape malformado derrubava o item | existia | existia |
| 7. Link transformado de outro repositório sem aviso | existia | não existia |

Para julgar os fluxos, também foram lidos, sem alteração, os pontos de outras áreas que as classes usam ou que as chamam: `RepositoryCloner`, `FileCloner`, `DependencyContext` e `DependencyHandler`, a abertura do `FileCloner` em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java:209`, a chamada de `rewriteHtml` de todos os handlers em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ContentCopier.java:331`, a regra de repositório obrigatório em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/validation/DependencyRules.java:111` e o desfazer de clones em `src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:613`.

### Arquivos temporários

`FileCloner` copia o arquivo da origem para um diretório temporário, e o `addData` do repositório de destino recebe o caminho dessa cópia. `FileManager#add` lê a cópia dentro de um `afterCommit` (`portal/src/java/lumis/portal/file/FileManager.java:616`), e `TransactionHibernate#commit` chama esses observadores de forma síncrona, antes de retornar (`portal/src/java/lumis/portal/dao/hibernate/TransactionHibernate.java:465`). O `try` com recurso de `ItemProcessor` fecha o `FileCloner` depois do `commit` no sucesso e na saída por exceção, e o rollback não usa a cópia. Os arquivos temporários são liberados nos dois casos. Os contextos de `precheck` e do desfazer recebem `FileCloner` nulo, e nenhum desses caminhos copia arquivo.

Cada cópia fica num subdiretório numerado próprio, com o nome físico da origem, então dois arquivos de mesmo nome no mesmo item não colidem. O tamanho do arquivo não exige tratamento à parte: a cópia é feita em blocos de 8 KB, com o hash calculado na passagem, e o limite de tamanho do repositório de destino é aplicado pelos validadores de `FileManager#add`, que fazem o item falhar com a mensagem da plataforma.

### Transação por item

Os handlers usam a transação e a conexão do contexto em todas as consultas próprias. `MediaManager#get` (`contentservices/src/java/lumis/service/media/MediaManager.java:28`) lê pela transação corrente da thread, e não pela transação recebida. Isso é correto aqui porque `PortalTransactionFactory#createTransaction` associa a transação do item à thread (`portal/src/java/lumis/portal/transaction/PortalTransactionFactory.java:91`), e assim os clones gravados no item são vistos pelas leituras seguintes. `GenericHibernateManualUpdateDao#get` lança `PortalObjectNotFoundException` quando a mídia ou o documento não existe (`portal/src/java/lumis/portal/dao/hibernate/GenericHibernateManualUpdateDao.java:62`), e o `load` dos dois handlers converte essa exceção em ausência.

### Concorrência e permissões

Os handlers são instâncias únicas do registro e não guardam estado entre chamadas. Os adaptadores são criados por chamada, e os `Pattern` estáticos são imutáveis. Execuções concorrentes são impedidas pelo lock global, revisado no review do núcleo.

`MediaManager#get`, `MediaManager#getFolderIdsByParentAndName` e os métodos equivalentes de `DocumentManager` não conferem permissão (`contentservices/src/java/lumis/service/document/DocumentManager.java:49`). `FileManager#changeFilename` exige `PortalPermissions.FILE_UPLOAD` do usuário da sessão (`portal/src/java/lumis/portal/file/FileManager.java:717`). A migração roda com o usuário que a iniciou, e a escrita no repositório passa pelas fontes nativas, com as regras delas.

### Links no HTML, pastas e transformações

`rewriteHtml` reescreve três formas de link: o download por `fileDownload.jsp?fileId=`, o caminho público `/data/files/` e o caminho público de arquivo transformado. `HtmlDataTypeUtil#processAreasHtml` aplica a reescrita a cada área do conteúdo dividido em áreas (`doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:291`). O link transformado vira download com o parâmetro `transform`, que `FileDownloadControllerHtml` atende (`portal/src/java/lumis/portal/file/FileDownloadControllerHtml.java:58`). As tabelas `lum_FTTransformationFile` e `lum_FTTransformedFile` e as colunas consultadas foram conferidas na definição da plataforma (`portal/src/java/lumis/portal/database/databasedefinition.xml:3784` e `portal/src/java/lumis/portal/database/databasedefinition.xml:3882`). A falta da transformação no destino gera aviso, como o README já registra.

Pastas de destino são reaproveitadas pelo título sob o mesmo pai e mapeadas como não criadas pela migração. Pastas criadas pela migração ficam no mapa, que é chaveado pelo par de repositórios, e são compartilhadas por todas as migrações entre esses repositórios. No desfazer, `isCloneReferenced` retém a pasta enquanto ela tiver filhos.

## Achados corrigidos

### 1. Link de download relativo ganhava o prefixo `null`

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:219`.
- Impacto: o grupo do host em `DOWNLOAD_URL` é opcional e vale `null` num link relativo. A concatenação produzia `null/lumis/portal/file/fileDownload.jsp?fileId=...`, e o link migrado deixava de funcionar. Como `rewriteHtml` de mídia roda em todo HTML com repositórios distintos, o defeito atingia qualquer link de download relativo, inclusive para arquivos de documento. O teste existente só conferia o `fileId` e não via o prefixo.
- Correção: o host ausente vira texto vazio, como o handler de documento já fazia.
- Teste: `MediaMigrationTest.clonesSharedMediaOnceRewritesFieldsAndHtmlRetriesAndUpdatesChangedBytes` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/MediaMigrationTest.java:353`) e as duas asserções equivalentes de `DocumentMigrationTest` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/DocumentMigrationTest.java:197` e `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/DocumentMigrationTest.java:201`).

### 2. Origem sem repositório do tipo derrubava o item

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:431` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:495`.
- Impacto: `ContentCopier` chama `rewriteHtml` de todos os handlers registrados, mesmo quando a instância não depende de repositório daquele tipo. `DependencyRules` só bloqueia origem com repositório e destino sem; origem sem repositório é permitida. Com o repositório da origem nulo, `isSameRepository` é falso, e o handler chamava `getOriginRepositoryInstanceId().equals(...)` ao achar um link para mídia ou documento: `NullPointerException`, e o item terminava com `Could not rewrite document URLs in HTML.` O `resolve` tinha o mesmo acesso. Só o `precheck` tratava o repositório desconhecido.
- Correção: `clonesReferences` concentra a condição nos dois handlers. Referências só são clonadas quando os dois repositórios são conhecidos e diferentes; nos demais casos, `precheck`, `resolve` e `rewriteHtml` mantêm os valores como estão.
- Teste: `MediaMigrationTest.keepsHtmlLinksWhenTheOriginHasNoMediaRepository` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/MediaMigrationTest.java:252`) e `DocumentMigrationTest.keepsHtmlLinksWhenTheOriginHasNoDocumentRepository` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/DocumentMigrationTest.java:219`). O `resolve` usa a mesma condição, mas não tem teste próprio: um campo de mídia ou de documento com valor numa instância sem esse repositório não é montável pela fonte nativa.

### 3. Pasta homônima escolhida pela ordem do banco

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:726`.
- Impacto: a plataforma só impede pasta homônima no mesmo pai pela ação de interface `CheckDuplicateFolderProcessActionHandler` (`contentservices/src/java/lumis/service/media/CheckDuplicateFolderProcessActionHandler.java:23`); a fonte aceita a gravação. Com duas pastas de mesmo título no destino, `MediaDaoHib#getFolderIdsByParentAndTitle` devolve os ids sem `order by` (`contentservices/src/java/lumis/service/media/MediaDaoHib.java:111`), e o handler reaproveitava a primeira. A pasta escolhida dependia do plano do banco e podia mudar entre bancos ou execuções, sem aviso ao usuário. O handler de documento já ordenava os ids e avisava.
- Correção: `findFolder` de mídia ordena os ids, reaproveita a pasta de menor id e registra o mesmo aviso do documento.
- Teste: `MediaMigrationTest.reusesTheSmallestIdAmongDuplicateDestinationFoldersAndWarns` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/MediaMigrationTest.java:236`).

### 4. Consulta de referência do desfazer sem `from`

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:369` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:434`.
- Impacto: `isCloneReferenced` usava `select case when exists (...) then 1 else 0 end` sem `from`. O Oracle anterior à versão 23ai recusa essa forma (ORA-00923), e no Oracle todo desfazer de clone de mídia ou de documento terminava com erro de SQL. O review da cópia e do desfazer registrou o mesmo padrão nestas classes, fora do escopo dele.
- Correção: a consulta conta a linha de `lum_ContentLocale` do clone com as condições `exists` correlacionadas, a mesma forma adotada em `MigrationUndoer#isContentReferenced`, aceita pelos três bancos. As tabelas e colunas foram conferidas na definição da plataforma (`contentservices/src/java/lumis/service/media/database/databasedefinition.xml:54` e `contentservices/src/java/lumis/service/document/database/databasedefinition.xml:43`).
- Teste: a falha no Oracle não é testável no ambiente local, que só tem MySQL. O README registra que Oracle e SQL Server não estão na matriz de integração (`README.md:121`). No MySQL, a consulta nova é exercida pelo handler registrado, com respostas verdadeira e falsa, em `MediaMigrationTest` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/MediaMigrationTest.java:178`) e em `DocumentMigrationTest` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/DocumentMigrationTest.java:203`).

### 5. Link absoluto movido de website levava o arquivo da origem

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:242`.
- Impacto: quando um link absoluto de download pertencia a um website diferente do website do repositório de destino, `rewriteAbsoluteUrl` remontava a URL a partir do link original. O host passava para o destino, mas a query continuava com o `fileId` da origem. O link migrado apontava para o arquivo da origem, e não para o clone.
- Correção: o método recebe a URL que já traz o `fileId` de destino e só troca a base do website; nos casos em que não troca, devolve essa mesma URL.
- Teste: não automatizado. O caso exige dois websites com URLs base distintas, e as fixtures do módulo só criam canais no website padrão. Os links relativos e os absolutos do mesmo website, que não passavam pela remontagem, seguem cobertos por `DocumentMigrationTest`.

### 6. Caminho público com escape malformado derrubava o item

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:543` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:632`.
- Impacto: `decodePath` usava `URLDecoder#decode`, que lança `IllegalArgumentException` com um escape como `%zz`. A exceção virava `PortalException` em `rewriteHtml`, e o item inteiro falhava. `HtmlDataType#checkHtmlPart` recusa URL malformada só em atributos de link (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1405`), mas as expressões dos handlers também acham `/data/files/...` no texto do HTML, que a plataforma aceita.
- Correção: um caminho que não decodifica fica como está, com um aviso no item.
- Teste: `MediaMigrationTest` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/MediaMigrationTest.java:354`) e `DocumentMigrationTest` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/migration/DocumentMigrationTest.java:202`), com o caminho malformado no texto do HTML.

### 7. Link transformado de outro repositório sem aviso

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:269`.
- Impacto: um link transformado para mídia de outro repositório ficava inalterado sem o aviso que os outros links e o handler de documento registram. O usuário não sabia que o link continuava na origem.
- Correção: o mesmo aviso de "belongs to another repository" é registrado.
- Teste: não automatizado. O arquivo transformado é gerado por uma tarefa assíncrona da fila de processos depois do commit, e o harness não configura transformações nos repositórios de teste.

### 8. Clareza

- `MediaDependencyHandler` ganhou `requireFile`, como o documento: um destino sem arquivo gera `IllegalStateException` com mensagem, em vez de `NullPointerException` (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:556`).
- O tipo `MEDIA` do mapa virou a constante `KIND_MEDIA`, como `KIND_DOCUMENT`; o valor gravado não mudou.
- `copyFile` confere o arquivo antes do `try` e não compara mais a mensagem da exceção para decidir se a relança, nos dois handlers (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:709` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:783`).
- O `delete` de `deleteClone` deixou de concatenar dois literais. Os nomes qualificados de `LinkedHashMap`, `Array` e `URLDecoder` viraram imports, e os imports sem uso (`HashSet` e `PortalUtil`) saíram.

## Achados adiados

### 1. Referência a item apagado tem tratamento diferente nos dois handlers

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:167` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:164`.
- Situação: quando a mídia referenciada por um campo não existe mais, o handler de mídia mantém o id antigo no destino. O de documento grava o campo vazio. Os dois registram aviso.
- Pergunta: o campo de destino deve ficar vazio nos dois casos, para não apontar para um item inexistente, ou manter o id antigo, para preservar a informação original?

### 2. Link absoluto de mídia mantém o website da origem

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:219`.
- Situação: o handler de documento move o link absoluto de download para o website do repositório de destino. O de mídia só troca o `fileId` e mantém o host da origem, que serve o arquivo enquanto os dois websites estiverem na mesma instalação.
- Pergunta: links absolutos de mídia devem seguir a regra do documento e passar para o website de destino?

### 3. `deleteClone` decide pela primeira linha do mapa

- Arquivos: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/media/MediaDependencyHandler.java:386` e `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/document/DocumentDependencyHandler.java:450`.
- Situação: duas pastas homônimas da origem podem apontar para a mesma pasta de destino, uma criada e outra reaproveitada. `deleteClone` recebe só o `ContentLocale` de destino, lê a primeira linha do mapa para saber se apaga e remove todas. Conforme a ordem, uma pasta criada pela migração fica no destino, ou é apagada ao desfazer a linha reaproveitada, para a qual o desfazer não confere referências (`src/br/com/lumis/commons/contentmigrator/service/contentmigration/undo/MigrationUndoer.java:613`).
- Pergunta: o desfazer deve identificar a linha do mapa que está sendo desfeita e apagar a pasta só ao desfazer a linha que a criou? A correção muda a assinatura de `DependencyHandler#deleteClone` e o desfazer, área de outro review.

### 4. Mesma mídia copiada várias vezes no modo atualização

- Arquivo: `src/br/com/lumis/commons/contentmigrator/service/contentmigration/dependency/RepositoryCloner.java:112`.
- Situação: no modo `UPDATE`, cada referência ao mesmo item no conteúdo (campos e links do HTML) chama `RepositoryCloner#resolve`, que copia o arquivo e atualiza o clone de novo. O resultado é correto, porque a segunda passagem vê o fingerprint já gravado, mas um arquivo grande é copiado tantas vezes quantas forem as referências.
- Pergunta: vale guardar, por item, o id de destino já resolvido? A memória teria de ficar em `DependencyContext`, fora destes dois pacotes.

## Verificações executadas

Comandos rodados a partir da raiz do módulo, com o resultado obtido. Os rótulos `VAL-*` nomeiam as verificações desta rodada de revisão; quando o comando importa, ele aparece entre parênteses.

- Prova de regressão antes da correção, com os testes novos e o código antigo implantado, em três rodadas de `bash test/run-tests.sh MediaMigrationTest DocumentMigrationTest`:
  - Na primeira, o caminho malformado estava num atributo `src`. `HtmlDataType#checkHtmlPart` recusou o HTML já na gravação da notícia de origem, com `STR_INVALID_URL_ON_HTML_FIELD_ERROR`, em cinco dos seis testes. Isso mostrou que a plataforma só valida URLs de atributos, e o caminho passou para o texto do HTML. O sexto teste, `keepsHtmlLinksWhenTheOriginHasNoDocumentRepository`, falhou com `Could not rewrite document URLs in HTML.` (achado 2).
  - Na segunda, terminou com `Tests run: 6, Failures: 6`. Todos os itens pararam com `Could not rewrite media URLs in HTML.` ou `Could not rewrite document URLs in HTML.` (achados 2 e 6). A falha de decodificação interrompia o item antes das demais asserções.
  - Na terceira, só com a correção de `decodePath` aplicada, terminou com `Tests run: 6, Failures: 5`, cada teste pela sua causa: `a relative download link gets no prefix` em `MediaMigrationTest` e `a relative download link in hierarchical HTML gets no prefix` em `DocumentMigrationTest` (achado 1, que também corrompia os links de documento), `Could not rewrite media URLs in HTML.` e `Could not rewrite document URLs in HTML.` nos testes de origem sem repositório (achado 2) e `duplicate folders are reported` (achado 3). `reusesMatchingDestinationFolderPathAndKeepsSameRepositoryReferences` passou.
- Depois da correção, todas as verificações terminaram com saída 0 e com MySQL e Elasticsearch saudáveis. `VAL-COMPILE` (`mvn clean test-compile`) compilou sem erro. `VAL-STYLE-DEP-FILES` verificou os dois arquivos de `media` e `document` sem violações. `VAL-STRUCTURE-TESTS` terminou com `PASS structure: 0 error(s), 0 warning(s)`. `VAL-STAGE2-MEDIA` terminou com `OK (4 tests)`, e `VAL-STAGE2-DOCUMENT`, com `OK (2 tests)`. `VAL-REVIEW` validou os relatórios de `docs/internal/review`, este incluído.
- `bash test/format.sh` sobre os quatro arquivos Java alterados só juntou numa linha o aviso de pastas homônimas de `MediaDependencyHandler` e quebrou de outro modo duas strings de `DocumentMigrationTest`. A mudança que ele fez numa consulta já existente de `MediaMigrationTest`, sem relação com este review, foi desfeita. `python3 test/check_style.py` sobre os quatro arquivos terminou sem violações.
