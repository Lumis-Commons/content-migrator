# Review dos testes e do harness

## Escopo revisado

Foram lidas as 22 classes `*Test` de `test/java/br/com/lumis/commons/contentmigrator/service/contentmigration/test/{admin,contract,migration,spike,support}`, as fixtures auxiliares e o runner `test/run-tests.sh`, além dos cinco relatórios de `docs/internal/review/`. A inspeção procurou colisões de dados, espera por tempo, dependência de ordem, limpeza ignorada, reflexão ambígua e testes sem asserções efetivas. Todos os métodos de teste têm ao menos uma asserção ou uma falha esperada.

O runner serializa execuções com `flock`, para o Tomcat só quando ele já estava no ar e o restaura no `EXIT`. O `--all` descobre classes `*Test.class`, ordena os nomes e exclui classes internas. Os testes de portal são sequenciais no `JUnitCore`; a sincronização do runner também impede duas JVMs do módulo sobre o mesmo banco.

## Achados corrigidos

| Risco observado | Teste ou correção que o cobre |
| --- | --- |
| Prefixos de fixture com só oito dígitos hexadecimais podiam colidir entre execuções, enquanto o UUID completo excede o limite do friendly id de canal. | `PortalFixture` e `ContentMetadataFixture` agora usam 20 dígitos hexadecimais (80 bits) do UUID, sem hífens: a entropia fica ampla para as execuções de teste e os nomes continuam dentro do limite da plataforma. `HarnessSmokeTest.fixtureCreatesAndRemovesChannelWithInstanceAndDependency` valida a criação dos canais. |
| As fixtures retornavam a contagem de falhas de limpeza, mas quase todos os `@After` a descartavam. A limpeza composta também podia esconder falhas das fixtures internas. | `TestCleanup` executa todos os limpadores, reúne contagens e exceções e falha o teardown ao final. Os `@After` de administração, contrato, migração e spikes verificam o resultado; `FullScenarioFixture.cleanup` verifica todos os limpadores internos. `TestCleanupTest.runsEveryCleanupAndReportsFailures` comprova que uma falha num limpador não impede os seguintes e que a falha é reportada. |
| A limpeza de posts de `SpiRulesTest` registrava exceções e continuava com sucesso. | O teardown agora registra cada exceção em `TestCleanup`, continua removendo os outros posts e falha depois da limpeza da fixture. |
| `DeleteUndoMigrationTest.tearDown` ignorava qualquer erro ao parar ou aguardar uma migração, embora só a migração apagada pelo próprio teste devesse ser ignorada. | O teardown agora só aceita o código `NOT_FOUND`; qualquer outro erro é propagado. Os testes de exclusão e desfazer dessa classe mantêm o caso da migração já removida. |
| `ModuleBridge.invokeStatic` e `newRow` escolhiam métodos apenas por nome e quantidade de parâmetros, sujeitos à ordem de `Class.getMethods()`. | A resolução agora considera tipo compatível, escolhe a sobrecarga mais específica e rejeita ambiguidades. `ModuleBridgeTest` cobre seleção de parâmetro específico, widening primitivo e sobrecargas indistinguíveis. |
| O runner apagava as classes instaladas antes de compilar. Uma falha de compilação podia deixar o Tomcat sem as classes anteriores; uma falha de deploy podia deixar o módulo parcialmente atualizado. | `test/run-tests.sh` compila primeiro, guarda o diretório instalado antes de removê-lo e o restaura no `EXIT` se o deploy não terminar. Em deploy bem-sucedido, o backup é descartado. A restauração do estado anterior do Tomcat continua independente desse backup. |
| A fixture de metadados precisava de versões em segundos diferentes para provar a cópia de autoria por versão; o teste não explicitava essa precondição nem conferia a data da versão publicada. | `ContentMetadataSpikeTest.cloneKeepsStateAssignedToAndPublishedActivePairWithoutTransitions` agora verifica que as datas da origem são distintas e que a data da versão publicada e da ativa foram copiadas individualmente. O `sleep(1100)` permanece porque a persistência nativa usa precisão de um segundo; é uma espera única, sem polling ou condição dependente de agendamento. |
| A espera da execução assíncrona podia ser confundida com uma espera fixa. | `LifecycleTest` usa polling com intervalo de 100 ms e limite de 60 segundos, falhando com diagnóstico quando a execução não termina. A espera valida estado persistido, e não um tempo de processamento presumido. |

## Mapa de cobertura: `nucleo.md`

| Risco revisado | Teste que o cobre ou limite da cobertura |
| --- | --- |
| `create` aceitar `updateMode` desconhecido, vazio ou de tipo incorreto. | `PersistenceContractTest.createAcceptsOnlyTheSupportedUpdateModes`. |
| Datas lidas como tipos diferentes no MySQL, Oracle e SQL Server. | `PersistenceContractTest.migrationDatesAreEpochMilliseconds` e `LifecycleTest.startRunsInBackgroundAsTheStarterAndFinishesWithOneMapRowPerContentLocale`. |
| Propriedades de execução malformadas deixarem a migração em `RUNNING`. | `LifecycleTest.malformedExecutionPropertiesFallBackToTheDefaultsAndTheRunFinishes`. |
| Ordem de locks invertida entre contadores, heartbeat e conclusão. | Não há teste determinístico de deadlock, pois o resultado depende do escalonamento e do plano do banco. `LifecycleTest.startRunsInBackgroundAsTheStarterAndFinishesWithOneMapRowPerContentLocale` verifica contadores e término; `LifecycleTest.runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus` verifica que o executor que perdeu o lock não confirma a escrita. |
| Falha de `close` substituir a exceção original da transação. | Sem injeção de falha no `TransactionHibernate#close`, não há regressão isolada possível neste módulo. O fluxo normal da API é exercido por `PersistenceContractTest`, `LifecycleTest`, `SimpleMigrationTest` e `DeleteUndoMigrationTest`. |
| Javadoc da fachada divergir das permissões e do comportamento real. | Não é risco comportamental; verificação por leitura do Javadoc e chamadas da API nos testes administrativos e de ciclo de vida. |
| Código morto e pré-condição escondida de `GlobalLock.databaseNow`. | Sem teste comportamental: a constatação é de uso e contrato público, sem mudança de comportamento. |
| Ordem das verificações no laço do desfazer ser difícil de seguir. | `DeleteUndoMigrationTest.undoStopsChildFirstAndCanRetryWhileKeepingNativeParentReference` cobre ordem e retomada observáveis; a clareza da ordem interna também foi conferida por leitura. |
| Migração antiga continuar em `RUNNING` após outra assumir um lock vencido. | `LifecycleTest.runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus` cobre o abandono seguro da escrita, mas não escolhe o status final da migração antiga. A transição de status segue adiada porque o produto ainda precisa decidir entre marcar `INTERRUPTED` ou permitir `stop`/`delete`. |
| `lum_cmig_MigRepoMap` não preservar o par quando a dependência muda depois da criação. | `DeleteUndoMigrationTest.deleteKeepsSharedRepositoryMapsUntilLastMigration` cobre o compartilhamento normal. Não há teste para troca de dependência depois da criação; a decisão entre usar essa tabela ou removê-la continua pendente. |
| Nome acima de 255 caracteres falhar com erro genérico de SQL. | Sem teste de limite: aguarda decisão entre limitar o formulário e criar um código de validação para a API. |
| `createdDate` e datas de execução divergirem entre relógios do app e do banco. | `PersistenceContractTest.migrationDatesAreEpochMilliseconds` verifica formato e proximidade no ambiente local, mas não há relógio de banco/app separado. A fonte temporal correta para cluster permanece uma decisão pendente. |
| MySQL com `useAffectedRows=true` retornar zero em atualizações sem mudança. | `LifecycleTest.runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus` e `DeleteUndoMigrationTest` cobrem os resultados com a configuração local. Nenhum teste muda a URL JDBC para `useAffectedRows=true`; a alternativa de SQL ou a restrição documentada da URL aguarda decisão. |

As corridas da tabela de `nucleo.md` têm cobertura parcial: `AdminFormValidationTest.creationRejectsDependencyRulesAndDuplicatePairs` cobre criação duplicada; `LifecycleTest.startIsRefusedWhileAnotherRunsAndStopEndsTheCurrentItemAndReleasesTheLock` cobre lock, parada e liberação; `DeleteUndoMigrationTest.deleteKeepsSharedRepositoryMapsUntilLastMigration` cobre exclusão de um dos pares que compartilham repositório. Não há teste que sincronize transações concorrentes para cada combinação `start`/`undo`/`delete`/`stop` nem a corrida `create`/`delete` do mesmo par. Essas lacunas ficam aceitas porque o resultado depende de coordenação específica do banco, enquanto as operações e os abortos são cobertos isoladamente pelas suítes citadas.

## Mapa de cobertura: `copia-desfazer.md`

| Risco revisado | Teste que o cobre ou limite da cobertura |
| --- | --- |
| Atualização e desfazer sem workflow numa instância que tem workflow. | `WorkflowMigrationTest.sourceWithoutWorkflowInAWorkflowInstanceIsUpdatedAndUndone`. |
| Fonte sem campo de idioma receber idioma padrão do portal. | `SimpleMigrationTest.sourceWithoutLanguageFieldKeepsTheOriginLocale`. |
| Público e instâncias de publicação serem copiados do destino em vez da origem. | `SimpleMigrationTest.publicationPrincipalsAndTargetsComeFromTheOriginPublication`. |
| Consulta do desfazer sem `from`, rejeitada pelo Oracle. | `DeleteUndoMigrationTest.undoStopsChildFirstAndCanRetryWhileKeepingNativeParentReference` exercita o caminho no MySQL. Oracle/SQL Server não fazem parte do ambiente local, conforme `README.md`; a compatibilidade nesses bancos não tem execução automatizada local. |
| Slug truncado numa barra ficar fora do formato nativo. | `SimpleMigrationTest.suffixedSlugCutAtASlashKeepsTheNativeSlugFormat`. |
| Javadoc diferente do comportamento. | Sem teste comportamental; verificação documental. |
| Clareza da cópia e do desfazer. | Sem teste próprio; os fluxos públicos são exercidos por `SimpleMigrationTest`, `WorkflowMigrationTest` e `DeleteUndoMigrationTest`. |
| `UPDATE` gerar versões mesmo sem alteração na origem. | Sem teste de ausência de mudança: decidir se `UPDATE` deve sempre regravar ou comparar a versão de origem. |
| Origem apagada durante uma execução interromper a migração. | Sem teste de exclusão concorrente; aguarda decisão entre pular com aviso ou parar a execução. |
| Referência a conteúdo ainda não migrado ficar vazia permanentemente em `ADD_ONLY`. | `SimpleMigrationTest.migratesLocalesTogetherAndRemapsHierarchyAndStandardRelations` cobre remapeamento com alvos existentes. Não há teste de alvo posterior na ordem de descoberta; ordenar pais/alvos ou fazer segunda passagem requer decisão de comportamento. |
| Campo lookup entre fontes do mesmo serviço continuar com id da origem. | Sem teste: a correção exige decidir se o motor remapeia esses campos ou se a criação recusa serviços com relações entre fontes. |
| Regras de dependência não serem revalidadas em `start`. | `AdminFormValidationTest.creationRejectsDependencyRulesAndDuplicatePairs` cobre a validação na criação, mas não uma mudança posterior de workflow/dependência. Revalidar no início é uma decisão fora do escopo deste review. |
| Aviso de publicação social nunca ser emitido. | Sem teste: o ambiente local não configura integração social; decidir se o aviso será removido ou se a origem do dado deve mudar. |

## Mapa de cobertura: `midia-documento.md`

| Risco revisado | Teste que o cobre ou limite da cobertura |
| --- | --- |
| Prefixo `null` em link de download relativo. | `MediaMigrationTest.clonesSharedMediaOnceRewritesFieldsAndHtmlRetriesAndUpdatesChangedBytes` e `DocumentMigrationTest.clonesSharedDocumentOnceRewritesReferencesRetriesAndUpdatesChangedBytes`. |
| Campo/HTML com origem sem repositório de mídia ou documento derrubar a cópia. | `MediaMigrationTest.keepsHtmlLinksWhenTheOriginHasNoMediaRepository` e `DocumentMigrationTest.keepsHtmlLinksWhenTheOriginHasNoDocumentRepository`. |
| Pasta homônima escolhida pela ordem não determinística do banco. | `MediaMigrationTest.reusesTheSmallestIdAmongDuplicateDestinationFoldersAndWarns`. |
| Consulta de referência do desfazer sem `from`. | Os testes de clone de mídia e documento exercitam o handler nativo com clones referenciados e não referenciados no MySQL. O Oracle permanece fora da matriz local. |
| Link absoluto entre websites levar o arquivo da origem. | Não automatizado: as fixtures só criam canais no website padrão, e o caso exige dois websites com URLs base diferentes. Links absolutos e relativos no mesmo website continuam cobertos pelos testes de clone de mídia/documento. |
| Escape malformado em caminho público derrubar o item. | Os testes de clone de mídia/documento conferem que o caminho malformado no HTML não interrompe a cópia. |
| Link de arquivo transformado de outro repositório não gerar aviso. | Não automatizado: a transformação é produzida assincronamente e o harness não configura transformações nos repositórios de teste. |
| Clareza dos handlers e do `FileCloner`. | `SpiRulesTest.fileClonerCreatesNewFileWithSameBytesNameAndContentType` cobre cópia dos bytes e metadados do arquivo; clareza interna foi revisada por leitura. |
| Campo para item apagado ter resultado diferente em mídia e documento. | Sem teste para unificar os resultados: decidir se o destino deve ficar vazio ou preservar o id antigo. |
| Link absoluto de mídia manter host de origem. | Não há caso multi-website pelas limitações das fixtures; decidir se o host deve acompanhar o website de destino. |
| `deleteClone` usar a primeira linha para decidir se apaga pasta compartilhada. | `DeleteUndoMigrationTest.undoRemovesUnusedClonesAndKeepsReusedAndReferencedDependencies` cobre reuso/referências ordinários. Não cobre duas pastas de origem homônimas que compartilham a mesma pasta de destino; corrigir exige mudar o contrato da SPI e do desfazer. |
| `UPDATE` copiar várias vezes a mesma mídia para múltiplas referências. | Os testes de update verificam fingerprint, bytes e id preservado; não medem a quantidade de cópias. Guardar resultado por item exige decisão sobre estado adicional em `DependencyContext`. |

## Mapa de cobertura: `spi-categorizacao-widget.md`

| Risco revisado | Teste que o cobre ou limite da cobertura |
| --- | --- |
| Embed órfão com repositório de widgets compartilhado derrubar o item. | `WidgetMigrationTest.removesOrphanEmbedsAndKeepsTheOthersWhenTheRepositoryIsShared`. |
| Origem sem repositório de categorização derrubar o item. | `CategorizationMigrationTest.keepsTermsWhenTheOriginHasNoCategorizationRepository`. |
| Cada locale do mesmo termo virar termo distinto no destino. | `CategorizationMigrationTest.addsEachTermLocaleToTheSameDestinationTerm`. |
| Consulta de referência de categoria sem `from`. | `CategorizationMigrationTest.resolvesPublishedAndActiveTermsByPathReusesDestinationAndRetriesMapConflict` verifica termo referenciado e não referenciado no MySQL; Oracle/SQL Server estão fora do ambiente local. |
| Contrato de chamada da SPI descrito incorretamente no Javadoc. | Sem teste comportamental; `SpiRulesTest.handlersAreRegisteredInTheEngineAndRunInOrder` verifica ordem de registro e chamada dos handlers no engine. |
| Clareza dos handlers de widget e categorização. | `SpiRulesTest.handlersAreRegisteredInTheEngineAndRunInOrder`, os testes de migração de widget e os testes de categoria cobrem os contratos públicos; nomes e comentários foram revisados por leitura. |
| Desfazer de um par apagar mapa/termo pertencente a outra origem. | `CategorizationMigrationTest.deleteCloneDoesNotDeleteAnotherOriginRepositoryMapOrTerm`. |
| Aspas simples criarem atributo duplicado no embed. | `WidgetMigrationTest.blocksWithoutMappingThenResumesAndRewritesEveryEmbed`. |
| Termo sem versão publicada ser omitido ou bloquear a cópia de forma inesperada. | Sem teste até definir se a versão ativa deve ser migrada, se o termo deve ser omitido ou se deve bloquear o item. |
| Dependência de categoria removida após criar migração. | `AdminFormValidationTest.creationRejectsDependencyRulesAndDuplicatePairs` cobre só a criação. A validação na execução aguarda decisão de produto sobre refazer `DependencyRules.check` no início. |

## Mapa de cobertura: `adaptadores.md`

| Risco revisado | Teste que o cobre ou limite da cobertura |
| --- | --- |
| Estado do mapeamento de widget ser revelado antes das permissões. | `WidgetMappingAdminTest.queryChecksPermissionsBeforeReportingMigrationState` e `WidgetMappingAdminTest.saveActionChecksPermissionsBeforeReportingMigrationState`; a gravação negada também é coberta por `saveActionRefusesUserWithoutEditPermissionOnDestinationRepository`. |
| Chaves de `strings.pt_br.xml` divergirem do inglês ou ficarem sem tradução representativa. | `WidgetMappingAdminTest.showsBlockedContentAndOnlyDestinationRepositoryWidgetsAndSavesMapping` chama as verificações de localização e paridade de chaves. |
| Listas de migrações/widgets materializadas antes da paginação consumirem memória. | Sem teste de volume: decidir retenção e limite de widgets por repositório antes de escolher paginação em banco ou limite de retenção. |
| Endpoint REST diferenciar tabela vazia de migrações não visíveis. | `StatusRestTest.refusesUserWithoutMigrationPermissions` fixa o `403` atual. A existência global é um risco aceito até decidir se as respostas devem ser uniformes. |

## Achados adiados

As lacunas sem teste acima estão deliberadamente registradas com o motivo: comportamento ainda depende de decisão do produto, cenário exige topologia ausente no ambiente local (Oracle, SQL Server, websites distintos ou integração social), ou defeito depende de coordenação/async que o harness atual não controla. Os testes de volume e de falhas artificiais no `close` também precisam de um contrato ou ponto de injeção antes de serem confiáveis.

## Verificações executadas

Os rótulos `VAL-*` nomeiam as verificações desta rodada de revisão; quando o comando importa, ele aparece entre parênteses.

- `VAL-COMPILE` (`mvn -B -q -f pom.xml clean test-compile`): passou.
- `VAL-STYLE-TESTS` (`python3 test/check_style.py test/java`): passou; 31 arquivos, 0 violações.
- `VAL-HARNESS` (`HarnessSmokeTest`, `ModuleBridgeTest` e `TestCleanupTest`): passou; 6 testes, 0 falhas, com MySQL e Elasticsearch saudáveis. O Tomcat permaneceu parado.
- `VAL-STRUCTURE-TESTS` (`python3 test/check_structure.py --strict-tests`): passou; 0 erros e 0 avisos.
- `VAL-REVIEW` (`python3 test/check_architecture.py --profile review docs/internal/review`): passou.
