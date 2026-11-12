# Migração de mídias — arquitetura

Documento para o agente que implementa o handler `media_repository` (DependencyHandler do PAT002) e os
testes dele. Leia como instrução: cada regra abaixo diz o que fazer, com a evidência no código do
Lumis Portal 18.1.1 (caminhos relativos a `Lumis_PortalJava/`) ou no módulo (caminhos relativos a
`br.com.lumis.commons.contentmigrator/`).

Contratos adotados: PAT001 r1 (persistência e mapeamento) e PAT002 r1 (SPI e regras de dependência).
São os rótulos dos dois contratos de projeto do módulo: o PAT001 está materializado nos
`databasedefinition.xml` e nos DAOs e é verificado por `PersistenceContractTest`; o PAT002, em
`DependencyHandler` e `DependencyRules`, é verificado por `SpiRulesTest`.
Este documento não redefine tabelas nem assinaturas; o que falta neles está em
[Propostas de contrato](#propostas-de-contrato).

Fora do escopo: o engine (laço de itens, lock, heartbeat) e o handler de repositório de documentos.

## Modelo de dados

- **lum_Media** — uma linha por versão de conteúdo do serviço `lumis.service.media`, source `default`
  do tipo `contentTable` (`contentservices/src/java/lumis/service/media/douidefinition.xml:5-27`).
  Colunas: `id` (= `lum_ContentVersion.itemId`), `type` (0 mídia, 1 pasta), `mediaFile` (fileId),
  `parentFolder`, `title`, `legend`, `credits`, `description`, `inheritPublishTo`
  (`contentservices/src/java/lumis/service/media/database/databasedefinition.xml:3-51`,
  entidade em `contentservices/src/java/lumis/service/media/Media.java:15-42`).
  - `description` existe na tabela e na entidade, mas **não** é campo da source `default`
    (`contentservices/src/java/lumis/service/media/douidefinition.xml:8-16`): `addData` não o grava.
    Copie-o depois do `addData`, na mesma transação, com `ManagerFactory.getEntityManager().merge`,
    como faz `contentservices/src/java/lumis/service/media/processaction/PublishHeritageProcessActionHandler.java:33-40`.
  - Metadados de conteúdo: `publishToPrincipals`, `tag` e `categorization`
    (`contentservices/src/java/lumis/service/media/douidefinition.xml:18-22`).
- **mediaFile / lum_File** — o campo `mediaFile` tem `dataType="file"`
  (`contentservices/src/java/lumis/service/media/douidefinition.xml:13`). A linha `lum_File`
  (`portal/src/java/lumis/portal/file/FileConfig.java:61-82`) guarda `fileId`, `name`, `fullPath`
  (relativo à pasta DATA, `files/<8 níveis de guid>/<nome>`, `portal/src/java/lumis/portal/file/FileManager.java:460-487`),
  `contentType`, `fileSize`, `serviceInstanceId` (a instância dona), `contentLocaleId` e metadados.
- **lum_MediaUsage** — `(mediaId, contentVersionId, sourceId, fieldId)`, com FK em cascata para
  `lum_Media` e para `lum_ContentVersion`
  (`contentservices/src/java/lumis/service/media/database/databasedefinition.xml:52-96`). É reescrita
  por versão e campo a cada add/update (`contentservices/src/java/lumis/service/abstraceentityusage/AbstractEntityUsageUtil.java:285-297`),
  a partir de:
  - campos `dataType="media"` (`contentservices/src/java/lumis/service/media/field/MediaDataType.java:367-394`);
  - campos HTML, só para links relativos `lumis/portal/file/fileDownload.jsp?...fileId=` cujo fileId é
    de uma mídia (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:123-124`,
    `doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:940-945`,
    `doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1361-1380`).
- **Valor de um campo media** — o `lum_Media.id` (itemId de uma versão). O conversor aceita a string
  do id e devolve `MediaDataType.Data` (`contentservices/src/java/lumis/service/media/field/MediaDataType.java:216-260`);
  a leitura faz join `lum_Media` → `lum_File` (`contentservices/src/java/lumis/service/media/field/MediaSourceField.java:35-55`).
  Apagar um conteúdo que usa a mídia não apaga a mídia
  (`contentservices/src/java/lumis/service/media/field/MediaDataType.java:361-365`).
- **SocialImage** — `lum_ContentVersion.socialImage` guarda um `lum_Media.id`
  (`content/src/java/lumis/content/core/ContentVersion.java:540-554`), exposto pelo campo de metadado
  `isSocialImage` (`content/src/java/lumis/content/table/ContentTableSource.java:366-367`) e gravado em
  `content/src/java/lumis/content/table/ContentMetaDataController.java:486-487`.
- **Busca por fileId** — `IMediaManager.getFromFileId` / `MediaUtil.getMediaIdFromFileId`
  (`contentservices/src/java/lumis/service/media/MediaDaoHib.java:32-46`,
  `contentservices/src/java/lumis/service/media/MediaUtil.java:105-114`) devolvem a primeira mídia com
  aquele `mediaFile`.

## Pastas

- Pasta é uma linha `lum_Media` com `type=1`, sem `mediaFile`, criada pela própria source `default`
  (interface `createFolder`, `contentservices/src/java/lumis/service/media/douidefinition.xml:445-480`).
  `parentFolder` aponta para o `lum_Media.id` da pasta pai; nulo é a raiz.
- A source `folder` (`contentservices/src/java/lumis/service/media/douidefinition.xml:36-42`) é virtual:
  `MediaFolderDataProvider` monta a árvore para a tela de mover
  (`contentservices/src/java/lumis/service/media/MediaFolderDataProvider.java:16-65`). Não migre essa
  source; só a `default` tem linhas.
- Nome único por pai: `CheckDuplicateFolderProcessActionHandler` recusa pasta com o mesmo título no
  mesmo pai (`contentservices/src/java/lumis/service/media/CheckDuplicateFolderProcessActionHandler.java:17-32`),
  usando `getFolderIdsByParentAndName` (`contentservices/src/java/lumis/service/media/IMediaManager.java:29`,
  `contentservices/src/java/lumis/service/media/MediaDaoHib.java:111-146`). `addData` direto não passa por
  esse handler; o handler de migração deve consultar o método antes de criar.
- Filhos: `getChildren` (`contentservices/src/java/lumis/service/media/MediaManager.java:50-68`) faz join
  com `lum_Content.serviceInstanceId`; o caminho virtual vem de `getFolderPath`
  (`contentservices/src/java/lumis/service/media/MediaManager.java:70-78`).
- Exclusão em cascata só existe na process action da tela
  (`contentservices/src/java/lumis/service/media/MediaDeleteProcessActionHandler.java:23-49`);
  `ContentTableSource.deleteDataByItemIds` não apaga filhos
  (`content/src/java/lumis/content/table/ContentTableSource.java:954-1000`).
- Permissões de pasta: `inheritPublishTo` decide se a mídia herda os principals do pai
  (`contentservices/src/java/lumis/service/media/Media.java:105-125`). A herança é calculada pela process
  action `PublishHeritageProcessActionHandler` (`contentservices/src/java/lumis/service/media/processaction/PublishHeritageProcessActionHandler.java:25-70`)
  e propagada por `updateMediaHierarchyPublishTo` (`contentservices/src/java/lumis/service/media/MediaManager.java:126-166`);
  sem pai, herda "todos os usuários" (`contentservices/src/java/lumis/service/media/MediaManager.java:187-208`).
  `addData` não executa essa process action, então o handler deve informar `publishToPrincipals`:
  - `inheritPublishTo=true`: `getMediaPublishToPrincipals(pastaPaiDestino)`;
  - `inheritPublishTo=false`: os principals das publicações da versão de origem
    (`ContentVersion.getPublications()`, `content/src/java/lumis/content/core/ContentVersion.java:336`).
    Principals são do portal, válidos nas duas instâncias.

Regra de cópia de pastas: crie a cadeia de pastas da origem, da raiz para a folha, antes da mídia.
Para cada pasta de origem:

1. Procure a linha `FOLDER` do MigItemMap (chave do PAT001 com `sourceId=default`). Destino existente:
   use-o.
2. Sem linha: procure no destino uma pasta com o mesmo título sob o pai já mapeado
   (`getFolderIdsByParentAndName`). Achou: reaproveite e grave a linha com `destCreated=false`.
3. Senão crie com `addData` (`type=1`, `title`, `parentFolder`, `inheritPublishTo`, `publishToPrincipals`)
   e grave a linha com `destCreated=true`, na transação do item.

## Transformações

- Transformações são configuradas por instância (`lum_FTTransformationFile`,
  `portal/src/java/lumis/portal/file/transformation/FileTransformation.java:30-33`). Ao gravar um arquivo,
  `FileManager.add` agenda na fila de processos a geração das transformações da instância dona
  (`portal/src/java/lumis/portal/file/FileManager.java:659-670`). O clone no destino recebe as
  transformações **do destino**, de forma assíncrona; não copie arquivos transformados nem linhas de
  `lum_FTTransformedFile` (`portal/src/java/lumis/portal/file/transformation/TransformedFileConfig.java:26-27`).
- `removeStoredFile` apaga as transformações do arquivo e o arquivo físico depois do commit
  (`portal/src/java/lumis/portal/file/FileManager.java:276-342`).
- URLs de transformação: `fileDownload.jsp?fileId=<id>&transform=<nome>`
  (`portal/src/java/lumis/portal/file/FileDownloadControllerHtml.java:45-58`,
  `portal/src/java/lumis/portal/file/FileDownloadControllerHtml.java:355-369`) ou, com armazenamento
  público, `data/<fullPath do transformado>` (`portal/src/java/lumis/portal/file/transformation/FileTransformationManager.java:557-585`).
  Ao reescrever HTML, mantenha `transform=<nome>`; se o destino não tem transformação com esse nome
  (`IFileTransformationManager.getFileTransformationsByServiceInstanceId`,
  `portal/src/java/lumis/portal/file/transformation/IFileTransformationManager.java:42`), grave aviso no item.
- As process actions `transform` e `reprocess` da tela
  (`contentservices/src/java/lumis/service/media/TransformFilesProcessActionHandler.java:23-43`,
  `contentservices/src/java/lumis/service/media/ReprocessFileTransformation.java:20-29`) não são usadas
  pela migração.

## Algoritmo de migração

Ponto de partida nativo: `TableSource.doCloneData` (`doui/src/java/lumis/doui/table/TableSource.java:618-690`)
copia o arquivo para um temporário e passa um `File` ao `addData`. Não o use diretamente:

- ele clona só dentro da mesma instância (`serviceSource` é da própria source);
- o conversor transforma `File` em `Data` com o **nome do temporário** e sem `contentType`
  (`doui/src/java/lumis/doui/source/field/type/FileDataType.java:78-86`);
- com o arquivo ausente ele remove o campo em silêncio (`doui/src/java/lumis/doui/table/TableSource.java:655-658`).

Copie a ideia, não a chamada. Para `resolve(campo, valorOrigem)` do handler de mídia (PAT002):

1. Origem e destino com a mesma instância de repositório: devolva o valor sem clone e sem linha de mapa.
2. `versaoOrigem = getContentVersionByItemId(mediaId)`; `localeOrigem = versaoOrigem.getContentLocale()`.
   Se o conteúdo não é da instância de repositório de origem do par, mantenha o valor e grave aviso.
3. Procure a linha `MEDIA` do MigItemMap com a chave do PAT001 (instâncias de repositório de origem e
   destino do MigRepoMap, `sourceId=default`, `originContentLocaleId`). Destino existente: siga
   [Mapeamento e reuso](#mapeamento-e-reuso) e devolva o `lum_Media.id` atual do ContentLocale de destino.
4. Sem destino: garanta a cadeia de pastas ([Pastas](#pastas)) e clone a mídia na transação do item:
   1. leia `lum_Media` e `lum_File` da origem (`MediaManager.get`, `FileManager.get`);
   2. copie o arquivo armazenado (`PortalBaseFolder.DATA.toPortalFilePath(fullPath)`) por stream para
      um diretório temporário próprio, com o mesmo nome físico; calcule SHA-256 e tamanho no caminho;
   3. `addData` na source `default` do repositório de destino com `title`, `legend`, `credits`,
      `type=0`, `parentFolder` mapeado, `inheritPublishTo`, `publishToPrincipals` e
      `mediaFile = FileDataType.Data(name e contentType da origem, fullPath da cópia)`. O campo grava um
      `lum_File` novo na instância de destino (`doui/src/java/lumis/doui/table/field/FileTableSourceField.java:186-238`);
   4. chame `IFileManager.setFileContentLocaleId(novoFileId, contentLocaleDestino)`
      (`portal/src/java/lumis/portal/file/IFileManager.java:304`). O metadado só o faz quando o parâmetro
      do campo converte para o fileId novo (`content/src/java/lumis/content/table/ContentMetaDataController.java:1941-1964`),
      e um `Data` sem fileId converte para nulo — o spike provou o valor nulo sem essa chamada;
   5. copie `description` com `merge` ([Modelo de dados](#modelo-de-dados));
   6. grave a linha `MEDIA` (PAT001) com `destCreated=true` e `originFileFingerprint`;
   7. apague o diretório temporário só depois do commit ou do rollback: o `FileManager` copia o arquivo
      no `afterCommit` (`portal/src/java/lumis/portal/file/FileManager.java:610-657`).
5. Idioma: crie no idioma do ContentLocale de origem, pelo parâmetro de idioma da source
   (`content/src/java/lumis/content/table/ContentMetaDataController.java:313-327`). Para o segundo idioma
   de um mesmo conteúdo de origem, informe o `contentId` de destino já mapeado (mesma rotina do engine
   para conteúdos), para que os idiomas fiquem no mesmo conteúdo.

Referências a reescrever no item que está sendo copiado (antes de `addData`/`updateData`, PAT002):

- **Campos `dataType="media"`**: troque o `lum_Media.id` de origem pelo `lum_Media.id` de destino de `resolve`.
- **SocialImage**: mesmo tratamento para o parâmetro do campo `isSocialImage`.
- **HTML**: percorra todas as áreas com `HtmlDataTypeUtil.processAreasHtml`
  (`doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:291`), que cobre o formato antigo e o
  JSON `lumHtmlContent` (`doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:51`,
  `doui/src/java/lumis/doui/source/field/type/HtmlDataTypeUtil.java:150`). Ordem: mídia, documento, widget.
  Formas a reconhecer, inclusive em URL absoluta (`https://host/ctx/...`) ou iniciada por `/`:
  1. download — `[prefixo]lumis/portal/file/fileDownload.jsp?<params>fileId=<32 hex><params>`: troque
     só o fileId, preserve prefixo, `inline` e `transform`;
  2. público — `[prefixo]data/files/<caminho>` (`portal/src/java/lumis/portal/file/FileManager.java:738-766`,
     `portal/src/java/lumis/portal/file/FileUtil.java:156-163`): ache o `lum_File` por
     `fullPath = files/<caminho decodificado>` e troque pela URL de `FileManager.getFileHref` do arquivo de destino;
  3. público transformado — `[prefixo]data/<fullPath de lum_FTTransformedFile>`: ache o original e o
     nome da transformação e troque por `lumis/portal/file/fileDownload.jsp?fileId=<destino>&transform=<nome>`,
     pois o transformado do destino ainda não existe.

  Só troque fileIds cuja mídia (`getMediaIdFromFileId`) pertence ao repositório de origem do par. Os
  demais ficam para o handler de documento ou permanecem com aviso.

## Mapeamento e reuso

- Linhas: `kind=MEDIA` para mídia e `kind=FOLDER` para pasta, `serviceId=lumis.service.media`,
  `sourceId=default`, instâncias do par de repositórios (PAT001). Uma linha por ContentLocale de origem.
- Chave de reuso: só o MigItemMap reaproveita mídias. Nunca reaproveite mídia do destino por nome ou
  por conteúdo; pasta pode ser reaproveitada por (pai mapeado, título) com `destCreated=false`.
- Nunca reaproveite o fileId da origem: `preDelete` do campo file apaga o arquivo armazenado
  (`doui/src/java/lumis/doui/source/field/type/FileDataType.java:1061-1082`), então um fileId compartilhado
  seria apagado pelo outro lado.
- Impressão digital (`originFileFingerprint`): `sha256:<hex minúsculo de 64>:<tamanho em bytes>` do
  arquivo armazenado da origem; nula em `FOLDER`. Trocar o arquivo de um campo file mantém o fileId
  (`doui/src/java/lumis/doui/table/field/FileTableSourceField.java:280-330`), então fileId não detecta mudança.
- Destino inexistente (apagado à mão): a linha conta como não migrada (PAT001); clone de novo e
  sobrescreva a linha (`destCreated=true`, ids renovados).
- Violação da chave única ao inserir a linha: reverta o item e tente de novo (PAT002); na nova tentativa
  a linha existe e o passo 3 do algoritmo a usa.

## Modo atualização

Com o `updateMode` da migração ativo e destino existente:

1. calcule a impressão digital da origem; diferente da linha: faça `updateData` no destino com novo
   `FileDataType.Data` (nome, contentType, cópia temporária) — o `lum_File` de destino mantém o fileId
   e recebe o arquivo novo — e grave a nova impressão digital;
2. mesma impressão digital e nome diferente: `IFileManager.changeFilename`
   (`portal/src/java/lumis/portal/file/IFileManager.java:253`);
3. sempre regrave `title`, `legend`, `credits`, `description`, `inheritPublishTo` e
   `publishToPrincipals`; pasta de origem diferente: mapeie a nova cadeia e grave `parentFolder`;
4. pasta: regrave título só se não colidir com outra pasta do mesmo pai; colisão vira aviso.

Sem o modo atualização: destino existente é devolvido sem alterações.

## Remoção no desfazer

Conta como **uso** de um destino `MEDIA` ou `FOLDER` (avalie depois de apagar os conteúdos do desfazer,
filhos antes dos pais, PAT001):

- `lum_MediaUsage.mediaId` igual ao `lum_Media.id` de **qualquer versão** do ContentLocale de destino;
- `lum_ContentVersion.socialImage` igual a qualquer desses ids;
- para pasta: qualquer `lum_Media` com `parentFolder` igual a qualquer desses ids.

Sem uso e com `destCreated=true`: apague com `ContentTableSource.deleteDataByItemIds` na source
`default` do repositório de destino (hooks nativos, PAT002), que remove `lum_File`, transformações e
arquivo físico, e apague a linha do mapa na mesma transação. Não use `MediaDeleteProcessActionHandler`:
ele apaga filhos sem checar uso. Com uso: mantenha e grave aviso. O spike prova que apagar o clone
mantém a origem.

## Falhas e bloqueios

| Situação | Ação |
|---|---|
| Arquivo armazenado da origem ausente | Falhe o item (`GENERIC`) com mediaId e fullPath; não imite a remoção silenciosa do `doCloneData`. |
| `FileValidationException` no destino (`portal/src/java/lumis/portal/file/FileManager.java:590-594`) | Falhe o item (`GENERIC`) com o campo e a mensagem. |
| Caminho relativo maior que 189 (`portal/src/java/lumis/portal/file/FileManager.java:484-487`) | Falhe o item (`GENERIC`). |
| Idioma da mídia não aceito no destino | `LOCALE_NOT_ACCEPTED` (PAT001), bloqueio no `precheck`. |
| Mídia de outro repositório | Mantenha o valor, aviso. |
| Pasta homônima no destino | Reaproveite, `destCreated=false`. |
| Transformação citada no HTML inexistente no destino | Mantenha `transform=<nome>`, aviso. |
| Usuário sem `FILE_UPLOAD` ao apagar (`portal/src/java/lumis/portal/file/FileManager.java:287-290`) | Falha do item de desfazer, com mensagem. |

Temporários são apagados num `finally` que roda depois do commit ou rollback da transação do item.

## Riscos

- URLs absolutas e públicas no HTML não entram em `lum_MediaUsage` (o padrão nativo exige aspas antes
  de `lumis/`). Um conteúdo nativo editado depois da migração com essa forma não impede o desfazer de
  apagar a mídia. Mitigação: aviso no desfazer; proposta P3.
- `lum_File.contentLocaleId` fica nulo se o passo 4.4 for esquecido; arquivos sem locale perdem o
  controle de visualização por publicação.
- `description` fora da source exige `merge`; se o portal passar a expor o campo, o `merge` duplica a escrita sem dano.
- Transformações assíncronas: links de transformação no destino podem responder 404 até a fila processar.
- Mais de uma versão por ContentLocale: o mapa é por locale; referências a versões diferentes do mesmo
  locale resolvem para a mesma mídia de destino (aviso quando a versão referenciada não é a copiada).
- Arquivos grandes: a cópia é por stream, mas ocupa disco temporário até o commit.

## Propostas de contrato

- **P1 (PAT001)** — fixar o formato de `originFileFingerprint` como `sha256:<hex64>:<bytes>`, nulo em
  `FOLDER`, para que o handler de documento use o mesmo formato.
- **P2 (PAT002)** — explicitar no contrato do desfazer que "referenciado" para repositório de mídia é a
  união de `lum_MediaUsage` (todas as versões), `lum_ContentVersion.socialImage` e filhos de pasta, e que
  todo clone com arquivo chama `setFileContentLocaleId` antes do commit.
- **P3 (PAT001)** — opcional: registrar, por item de conteúdo copiado, os fileIds de destino escritos em
  HTML com URL absoluta ou pública, para que o desfazer os conte como uso. Exige coluna ou tabela nova;
  sem aprovação, fica o aviso do risco.

## Evidências

- Spike `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/MediaCloneSpikeTest.java:71-137`:
  clona mídia entre dois repositórios em pastas, com `cloneMedia`
  (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/MediaCloneSpikeTest.java:145-181`).
  Prova fileId diferente, mesmo nome, contentType, tamanho e bytes, `serviceInstanceId` do destino,
  `contentLocaleId` do destino e, depois de apagar o clone pela source, `lum_File` e arquivo físico do
  clone removidos e os da origem intactos.
- Fixture `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/MediaFixture.java:112-145`:
  `addData`, `linkFileToContentLocale` e `fileData`, reutilizáveis pelo handler e seus testes.
- Primeira execução sem `linkFileToContentLocale`: falhou em
  `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/MediaCloneSpikeTest.java:113` com
  `contentLocaleId` nulo; motivo em `content/src/java/lumis/content/table/ContentMetaDataController.java:416-424`.
- Validação: `VAL-SPIKE-MEDIA` (`bash test/run-tests.sh MediaCloneSpikeTest`) e `VAL-ARCH-MEDIA`
  (`python3 test/check_architecture.py docs/architecture/media-migration.md`).

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
- Gravação no repositório de mídia: `createItem` e `updateItem` do `Adapter` montam os dados com
  `buildMediaData(origin, destination, parentId, fileToWrite, titleToWrite)` e gravam com
  `save(origin, destination, data, destinationContentId)`. Inclusão é `destination == null`, e o arquivo
  só é gravado quando `fileToWrite` não é nulo: `updateItem` passa `fileChanged ? file : null`, e as
  pastas passam `null`. `syncMediaFile(origin, destination, target, itemId, isFileWritten)` sincroniza
  o `lum_File`. Nenhum desses métodos recebe flag de inclusão ou de gravação do arquivo.
