# Migração de documentos — arquitetura

Documento para o agente que implementa o handler `document_repository` (DependencyHandler do PAT002) e
os testes dele. O handler de documento segue o de mídia; leia antes
`docs/architecture/media-migration.md:1-307`. Aqui estão **só as diferenças**: o que não é citado vale
como na mídia (clone de `lum_File`, transformações, impressão digital, temporários, idioma). Caminhos
relativos a `Lumis_PortalJava/` (portal) ou a `br.com.lumis.commons.contentmigrator/` (módulo).

Contratos adotados: PAT001 r1 (persistência e mapeamento) e PAT002 r1 (SPI e regras de dependência).
São os rótulos dos dois contratos de projeto do módulo: o PAT001 está materializado nos
`databasedefinition.xml` e nos DAOs e é verificado por `PersistenceContractTest`; o PAT002, em
`DependencyHandler` e `DependencyRules`, é verificado por `SpiRulesTest`.
Este documento não redefine tabelas nem assinaturas; o que falta neles está em
[Propostas de contrato](#propostas-de-contrato).

Fora do escopo: o engine e o código do handler; repetir o que já está no documento de mídia.

## Modelo de dados

- **lum_Document** — serviço `lumis.service.document`, source `default` do tipo `contentTable`
  (`contentservices/src/java/lumis/service/document/douidefinition.xml:5-30`). Colunas `id`, `type`
  (0 documento, 1 pasta), `documentFile` (fileId), `parentFolder`, `title`, `description` (500) e
  `inheritPublishTo` (`contentservices/src/java/lumis/service/document/database/databasedefinition.xml:3-35`,
  entidade `contentservices/src/java/lumis/service/document/DocumentConfig.java:15-38`).
  Diferenças da `lum_Media` (`docs/architecture/media-migration.md:16-26`):
  - não há `legend` nem `credits`;
  - `description` **é** campo da source (`contentservices/src/java/lumis/service/document/douidefinition.xml:11-15`):
    passe-o no `addData`/`updateData` e não use o `merge` da mídia;
  - `documentFile` é `required="true"` (`contentservices/src/java/lumis/service/document/douidefinition.xml:10`):
    documento sem arquivo não é gravado; pasta não passa o campo (o spike cria pasta pela source sem ele).
  - a source tem `bigData` próprio, que não indexa publicações de pastas
    (`contentservices/src/java/lumis/service/document/DocumentBigDataPersister.java:13-24`); nada a fazer
    na migração, só não estranhe pasta ausente da busca.
- **documentFile / lum_File** — `dataType="file"`, igual a `mediaFile`. Vale a mesma armadilha de
  `contentLocaleId`: o metadado só grava o locale em campos cuja classe é exatamente `FileDataType`
  (`content/src/java/lumis/content/table/ContentMetaDataController.java:1941-1952`) e um `Data` sem fileId
  converte para nulo; chame `IFileManager.setFileContentLocaleId` depois do `addData` (o spike prova).
- **lum_DocumentUsage** — `(documentId, contentVersionId, sourceId, fieldId)`, FKs em cascata para
  `lum_Document` e `lum_ContentVersion`
  (`contentservices/src/java/lumis/service/document/database/databasedefinition.xml:41-85`). Mesma
  mecânica de `lum_MediaUsage` (`DocumentUtil` estende `AbstractEntityUsageUtil`,
  `contentservices/src/java/lumis/service/document/DocumentUtil.java:21-49`), reescrita por versão e
  campo (`contentservices/src/java/lumis/service/abstraceentityusage/AbstractEntityUsageUtil.java:285-297`).
  Fontes:
  - campos `dataType="document"` (`contentservices/src/java/lumis/service/document/field/DocumentDataType.java:372-399`);
  - campos HTML, com o mesmo padrão `fileDownload.jsp?...fileId=` da mídia
    (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:123-124`), traduzido para documento por
    `getDocumentIdFromFileId` (`doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:947-963`,
    `doui/src/java/lumis/doui/source/field/type/HtmlDataType.java:1185-1200`).
- **Sem SocialImage**: documentos não são referenciados por `lum_ContentVersion.socialImage`; ignore essa
  parte de `docs/architecture/media-migration.md:39-42`.
- **Busca por fileId** — `IDocumentManager.getFromFileId` / `DocumentUtil.getDocumentIdFromFileId`
  (`contentservices/src/java/lumis/service/document/DocumentDaoHib.java:43-56`,
  `contentservices/src/java/lumis/service/document/DocumentUtil.java:59-68`).
- **`IDocumentManager.get` lança** `PortalObjectNotFoundException` para id inexistente, apesar do javadoc
  dizer nulo (`contentservices/src/java/lumis/service/document/IDocumentManager.java:13-20`,
  `portal/src/java/lumis/portal/dao/hibernate/GenericHibernateManualUpdateDao.java:63-69`). Trate a exceção
  como "não existe" ao verificar destino do mapa e referência pendente.

## Referências por dataType document

- **Valor** — o `lum_Document.id`. O conversor aceita `DocumentConfig`, `DocumentDataType.Data` ou a string
  do id, busca o documento e converte para `String` devolvendo o id
  (`contentservices/src/java/lumis/service/document/field/DocumentDataType.java:205-271`). A leitura faz
  join `lum_Document` → `lum_File` (`contentservices/src/java/lumis/service/document/field/DocumentSourceField.java:21-113`).
- **Campos conhecidos** — `linkDocumentId` do conteúdo hierárquico (coluna guid,
  `contentservices/src/java/lumis/service/hierarchicalcontent/database/databasedefinition.xml:29-31`; campo
  `contentservices/src/java/lumis/service/hierarchicalcontent/douidefinition.xml:37`, usado quando
  `type=3`, `contentservices/src/java/lumis/service/hierarchicalcontent/douidefinition.xml:18-24`), e o mesmo
  campo em destaque (`contentservices/src/java/lumis/service/highlight/douidefinition.xml:25`) e em
  experiência (`contentservices/src/java/lumis/service/experiencecontent/douidefinition.xml:28`). O handler
  trata **qualquer** campo cujo dataType é `document`, não esses ids.
- O link do hierárquico é calculado na indexação a partir do documento e do arquivo
  (`contentservices/src/java/lumis/service/hierarchicalcontent/HierarchicalContentBigDataPersister.java:92-110`):
  referência a pasta gera link nulo. A tela só deixa escolher documento
  (`contentservices/src/java/lumis/service/document/DocumentSelectProcessActionHandler.java:37-39`), mas
  pela API o valor pode ser uma pasta: resolva pela linha `FOLDER` do mapa.
- O conversor busca o documento a cada conversão; valor apontando para documento apagado gera
  `ConversionException` (`contentservices/src/java/lumis/service/document/field/DocumentDataType.java:225-234`).
  O handler testa a existência antes de converter (ver [Falhas e bloqueios](#falhas-e-bloqueios)).
- Apagar o conteúdo que referencia não apaga o documento: `deleteRelatedFile` é vazio
  (`contentservices/src/java/lumis/service/document/field/DocumentDataType.java:366-370`).
- Repositório do campo: dependência `document_repository` da instância de conteúdo
  (`portal/src/java/lumis/portal/serviceinstance/ServiceInstanceDependencyConfig.java:60`,
  `contentservices/src/java/lumis/service/document/FCKSelectDocumentControllerHtml.java:47-59`); o par vem
  do MigRepoMap (PAT001).

## Links de arquivo no HTML

Formas e reescrita iguais às da mídia (`docs/architecture/media-migration.md:163-180`), na ordem
mídia, documento, widget (PAT002). Diferenças:

- O handler de documento troca só os fileIds cujo documento (`getDocumentIdFromFileId`) é do repositório
  de documentos de origem do par; o de mídia já trocou os seus antes. FileId de mídia não é documento:
  cada `lum_File` pertence a uma única linha.
- O seletor de documento do editor grava `fileDownload.jsp?fileId=` e, quando o arquivo é de outro
  website, prefixa a URL base desse website
  (`contentservices/src/java/lumis/service/document/DocumentSelectProcessActionHandler.java:47-63`). URL
  absoluta é portanto comum em documentos, e ela **não** entra em `lum_DocumentUsage` (o padrão exige aspas
  antes de `lumis/`). Reescreva também essa forma, mas com o host de origem; se a instância de destino
  estiver em outro website, troque o prefixo pela URL base do website do destino
  (`IWebsite.getMainNonSecureBaseURL`/`getMainSecureBaseURL`, mantendo o esquema), senão mantenha e grave aviso.
- Documentos não têm transformações configuradas por padrão, mas a regra de `transform=<nome>` da mídia
  vale se a instância tiver.

## Pastas

Mesmo modelo e regra de cópia de `docs/architecture/media-migration.md:57-97`, com estas diferenças:

- Busca por pai e título: `IDocumentManager.getFolderIdsByParentAndName(session, instanceId, pai, título,
  transação)` (`contentservices/src/java/lumis/service/document/IDocumentManager.java:31`,
  `contentservices/src/java/lumis/service/document/DocumentDaoHib.java:121-156`) recebe sessão e transação.
- **O destino pode já ter pastas homônimas**: o `CheckDuplicateFolderProcessActionHandler` lê o parâmetro
  `name` (`contentservices/src/java/lumis/service/document/CheckDuplicateFolderProcessActionHandler.java:16-19`),
  mas a tela envia `title` (`contentservices/src/java/lumis/service/document/douidefinition.xml:298-322`), então
  a checagem nativa compara com nulo e não bloqueia. O mesmo vale para a mídia
  (`contentservices/src/java/lumis/service/media/CheckDuplicateFolderProcessActionHandler.java:21-24`). Com
  mais de uma pasta candidata, reaproveite a de menor `id` (determinístico) e grave aviso; com uma, reaproveite.
- Filhos: `IDocumentManager.getChildren(session, instanceId, pasta, tipo, transação)`
  (`contentservices/src/java/lumis/service/document/DocumentManager.java:56-74`).
- Permissões: `PublishHeritageProcessActionHandler` do documento
  (`contentservices/src/java/lumis/service/document/processaction/PublishHeritageProcessActionHandler.java:25-65`)
  calcula `publishToPrincipals` como o da mídia; use `getDocumentPublishToPrincipals(pastaPaiDestino)` para
  `inheritPublishTo=true` (`contentservices/src/java/lumis/service/document/DocumentManager.java:197-218`).
- Mover pasta exige `moveDocument`, que propaga a herança
  (`contentservices/src/java/lumis/service/document/DocumentManager.java:86-131`); no modo atualização,
  troque `parentFolder` por `updateData` e informe `publishToPrincipals` como na criação.
- Exclusão em cascata só na process action da tela
  (`contentservices/src/java/lumis/service/document/DocumentDeleteProcessActionHandler.java:23-49`); não a use.

## Algoritmo de migração

Siga o passo a passo de `resolve` da mídia (`docs/architecture/media-migration.md:132-158`) trocando:

| Mídia | Documento |
|---|---|
| `lumis.service.media`, `kind=MEDIA` | `lumis.service.document`, `kind=DOCUMENT` |
| `MediaManager.get(id)` | `DocumentManagerFactory.getDocumentManager().get(session, id, transação)` |
| `title`, `legend`, `credits`, `type=0`, `mediaFile` | `title`, `description`, `type=0`, `documentFile` |
| `description` por `merge` | `description` no próprio `addData` |
| `getMediaPublishToPrincipals` | `getDocumentPublishToPrincipals` |

Clone de `documentFile`: cópia por stream para diretório temporário próprio com o nome físico da
origem, `FileDataType.Data` com nome e contentType da origem e `fullPath` da cópia,
`setFileContentLocaleId` na mesma transação e remoção do temporário depois do commit — como no spike,
`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/DocumentCloneSpikeTest.java:150-185`.

Referências a reescrever no item copiado (antes de `addData`/`updateData`, PAT002):

- campos `dataType="document"`: troque o `lum_Document.id` de origem pelo de `resolve` (`DOCUMENT` ou,
  se for pasta, `FOLDER`);
- HTML: como em [Links de arquivo no HTML](#links-de-arquivo-no-html).

## Mapeamento e reuso

Iguais aos da mídia (`docs/architecture/media-migration.md:181-196`) com `kind=DOCUMENT`/`FOLDER`,
`serviceId=lumis.service.document`, `sourceId=default`. Mesma impressão digital (proposta P1 da mídia)
e mesma proibição de reaproveitar fileId: o `documentFile` é `dataType="file"`, cujo `preDelete` apaga
o arquivo armazenado (`doui/src/java/lumis/doui/source/field/type/FileDataType.java:1061-1082`). Diferença:
pasta homônima múltipla no destino, ver [Pastas](#pastas).

## Modo atualização

Igual ao da mídia (`docs/architecture/media-migration.md:198-211`), regravando `title`, `description`,
`inheritPublishTo` e `publishToPrincipals` pelo `updateData` (sem `legend`, `credits` nem `merge`).

## Remoção no desfazer

Conta como **uso** de um destino `DOCUMENT` ou `FOLDER`:

- `lum_DocumentUsage.documentId` igual ao `lum_Document.id` de qualquer versão do ContentLocale de destino;
- para pasta: qualquer `lum_Document` com `parentFolder` igual a esses ids;
- **não** há `socialImage`.

Sem uso e com `destCreated=true`: `ContentTableSource.deleteDataByItemIds` na source `default` do
repositório de destino, que remove `lum_File` e arquivo físico; o spike prova que a origem fica intacta.
Com uso: mantenha e grave aviso. URL absoluta de documento no HTML (comum, ver
[Links de arquivo no HTML](#links-de-arquivo-no-html)) não conta como uso; é o risco R1.

## Falhas e bloqueios

Tabela da mídia (`docs/architecture/media-migration.md:228-241`) vale inteira, mais:

| Situação | Ação |
|---|---|
| Campo `document` aponta para documento inexistente na origem | Mantenha o valor nulo no destino e grave aviso com o campo e o id; não deixe o conversor lançar `ConversionException`. |
| Campo `document` aponta para pasta | Resolva como `FOLDER`; aviso (o link do hierárquico fica nulo, como na origem). |
| Pasta homônima múltipla no destino | Reaproveite a de menor id, `destCreated=false`, aviso. |
| URL absoluta de outro website no HTML | Reescreva fileId e prefixo do website do destino; sem website no destino, mantenha o prefixo e grave aviso. |
| Documento de origem sem `documentFile` (dado legado) | Falhe o item (`GENERIC`): o campo é obrigatório no destino. |

## Riscos

- **R1** — URL absoluta de documento é a forma padrão entre websites e não entra em `lum_DocumentUsage`;
  o desfazer pode apagar um documento ainda linkado por conteúdo nativo editado depois. Mitigação:
  aviso no desfazer; proposta P3 da mídia cobre também documentos.
- **R2** — Pastas homônimas existem no destino porque a checagem nativa não funciona; o reaproveitamento
  escolhe uma delas e pode não ser a que o usuário espera.
- **R3** — `IDocumentManager.get` lança em vez de devolver nulo; código que confie no javadoc quebra na
  verificação "destino existe" do PAT001.
- **R4** — Referência a pasta por `dataType="document"` é aceita pelo conversor e gera link nulo; a
  migração preserva o comportamento.
- **R5** — Documentos grandes (PDF, vídeos) ocupam disco temporário até o commit e alongam a transação
  do item; o heartbeat do PAT001 cobre o lock, mas o item pode estourar o limite do item corrente.

## Propostas de contrato

- **P1 (PAT002)** — explicitar que "referenciado" para repositório de documentos é a união de
  `lum_DocumentUsage` (todas as versões) e filhos de pasta, sem `socialImage`, e que todo clone com
  arquivo chama `setFileContentLocaleId` antes do commit (mesma proposta P2 da mídia, estendida).
- **P2 (PAT002)** — o handler resolve campos pelo dataType `document` (não pelo id `linkDocumentId`) e
  aceita valor que é pasta, devolvendo o destino `FOLDER`.
- **P3 (PAT001)** — fixar a regra de reaproveitamento de pasta homônima múltipla (menor id, aviso) para
  mídia e documento, já que a checagem nativa de duplicidade não atua.

## Evidências

- Spike `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/DocumentCloneSpikeTest.java:70-142`:
  cria duas instâncias de `lumis.service.document`, pastas e um documento com descrição; clona com
  `cloneDocument` (`test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/spike/DocumentCloneSpikeTest.java:150-185`).
  Prova fileId diferente, mesmos nome, contentType, tamanho e bytes, `description` gravada pelo `addData`,
  `serviceInstanceId` e `contentLocaleId` do destino, pasta do destino achada por
  `getFolderIdsByParentAndName` e, depois de apagar o clone pela source, `lum_File` e arquivo físico do
  clone removidos e os da origem intactos.
- Fixture `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/support/DocumentFixture.java:52-220`:
  `addFolder`, `addDocument`, `addData`, `linkFileToContentLocale`, `deleteDocument` e `cleanup`,
  reaproveitando os auxiliares de arquivo do `MediaFixture`.
- Validação: `VAL-SPIKE-DOCUMENT` (`bash test/run-tests.sh DocumentCloneSpikeTest`) e
  `VAL-ARCH-DOCUMENT` (`python3 test/check_architecture.py docs/architecture/document-migration.md`).

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
- Gravação no repositório de documento: `createItem` e `updateItem` do `Adapter` montam os dados com
  `buildDocumentData(origin, destination, parentId, fileToWrite, titleToWrite)` e gravam com
  `save(origin, destination, data, destinationContentId)`, sem flag de inclusão ou de gravação do
  arquivo. A falta do arquivo de origem é recusada antes de qualquer gravação, em
  `createItem` (arquivo nulo) e em `updateItem` (`fileChanged` com arquivo nulo), com a mesma exceção.
