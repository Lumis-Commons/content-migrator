# Decisões técnicas do Content Migrator

Registro das escolhas vigentes. IDs são estáveis e não devem ser reutilizados. Caminhos partem da raiz do módulo. Os estudos citados preservam o levantamento histórico; a seção de decisão implementada prevalece sobre propostas anteriores do mesmo estudo.

## CM-D001 — Identidade e contratos da renomeação

**Contexto.** O módulo foi renomeado na atualização para LumisXP 19.0.0 e precisa de identidade própria, coerente entre deploy, Java, REST, propriedades e testes, preservando os dois serviços existentes.

**Escolha.** Adotar a matriz de nomes de `docs/internal/migracao-19/MAPA-RENOMEACAO.md`, seção “Decisões de nomes”. Não criar aliases legados. Conservar nomes de classes, sufixos de propriedades, IDs locais de interfaces e GUIDs que o mapa mantém. As exceções do verificador de renomeação ficam somente na seção “Mantidos sem renomear” desse mapa.

**Evidência.** O mapa confronta os resolvedores de IDs do Portal e os descritores do módulo; `src/META-INF/lumis-module.xml` materializa a identidade. `test/check_rename.py --scope all` verifica ocorrências conforme as exceções declaradas no mapa. A matriz é a fonte dos nomes completos, sem reproduzi-la neste registro.

## CM-D002 — Persistência independente

**Contexto.** Os serviços precisam de tabelas distintas entre si e de nomes de constraints compatíveis com o limite de 30 caracteres definido para esta migração.

**Escolha.** Adotar o prefixo e os sufixos definidos em “Tabelas e constraints” do mapa citado em CM-D001, preservando a distinção hierárquica. A mudança de identidade não converte dados nem renomeia tabelas de uma instalação anterior. A instalação nova e a remoção são operações explícitas do Portal, descritas em `docs/OPERAR-MODULO-NO-PORTAL.md`.

**Evidência.** O apêndice de persistência do mapa enumera 11 tabelas e 20 constraints, com maior nome de 27 caracteres. Os arquivos `componentfiles/setup/databasedefinition.xml` dos dois serviços declaram os nomes e seus `component-uninstall-script.xml` definem a remoção. A busca no código da plataforma registrada no estudo não comprova ausência de colisões em um banco instalado.

## CM-D003 — Base de compilação e APIs do LumisXP 19.0.0

**Contexto.** O runtime usa a stack Jakarta; os JARs locais têm versão de desenvolvimento e não permitem deduzir uma coordenada Maven apenas pelo nome da pasta do ambiente.

**Escolha.** Usar a base Maven fixada em `pom.xml` e Java 21, conforme a seção “Decisão vigente” de `docs/internal/migracao-19/UPGRADE-19-0-0.md`. Migrar consumidores JAX-RS para Jakarta preservando contratos. Manter APIs `javax` que pertencem ao Java SE, namespaces XML Lumis e parâmetros JDBC. Não introduzir migração de Hibernate em DAOs que usam JDBC.

**Evidência.** O estudo identifica a base disponível `19.0.0.260630-enterprise`, sua comparação parcial com os binários locais e a limitação dessa comparação. Seus IDs BUILD e D1900 fundamentam a stack; “Resultado das buscas de compatibilidade” identifica APIs usadas e ausentes. A compilação contra essa API não afirma identidade binária com todos os JARs instalados.

## CM-D004 — Comandos transitórios de URLs

**Contexto.** O estudo inicial propunha preservar dois novos metadados SEO. A inspeção posterior da API demonstrou que são comandos transitórios, não preferências persistidas de uma versão de conteúdo.

**Escolha.** Omitir explicitamente `RecalculateFriendlyURLs` e `AddFriendlyURLsRedirects` nos dois serviços, em inclusão e atualização e em qualquer locale. Não fabricar valores ausentes nem consultar getters inexistentes. Preservar a política dos metadados copiáveis de URL e robots; o destino realiza seu processamento nativo.

**Evidência.** A seção “Decisão vigente” do estudo citado em CM-D003 corrige a hipótese inicial, apoiada nas fontes META19 e no controlador de metadados. Os `copy/MetadataPolicy.java` e os `SpiRulesTest.java` de ambos os serviços materializam essa escolha. O registro da decisão não equivale à execução desses testes de Portal.

## CM-D005 — Instruções curtas e documentação roteada

**Contexto.** Repetir contratos e procedimentos nos AGENTS.md aumenta o conteúdo carregado para tarefas que não precisam desses detalhes e cria fontes divergentes.

**Escolha.** Manter regras transversais e rotas nos pais; regras exclusivas nos filhos; decisões neste registro e procedimentos no guia operacional. Os critérios, limites e checklist ficam em `docs/GUIA-AGENTS-MD.md`.

**Evidência.** O modelo consultado foi o guia equivalente de outro módulo LumisXP, adaptado às pastas deste módulo. `AGENTS.md`, `src/AGENTS.md` e `test/AGENTS.md` contêm as rotas. `test/check_agents_md.py` confere os limites, as rotas e os propósitos definidos no guia.

## CM-D006 — Compilação do módulo aberto

**Contexto.** O módulo aberto pode carregar classes diretamente de `src`; compilar apenas a saída padrão deixa essa raiz sem as classes atualizadas.

**Escolha.** Usar o perfil local já definido no POM quando a raiz aberta for `src`, preservando fontes e recursos durante `clean`. A sequência e a pendência da ferramenta externa ficam em `docs/OPERAR-MODULO-NO-PORTAL.md`, seção “Compilar e publicar”.

**Evidência.** `pom.xml`, perfil `local-development`, altera a saída do compilador sem tornar `src` o diretório de build. `UpdateRunner.java`, método `mavenCommand`, linha 1964 da cópia externa consultada, só acrescenta esse perfil para o ID de outro módulo. Não se altera a ferramenta externa nesta decisão.

## CM-D007 — Reuso de repositório compartilhado e par (X,X)

**Contexto.** Origem e destino podem usar o mesmo repositório de mídia, documento, widget, tag ou categorização. Clonar itens para o repositório em que eles já estão duplicaria dados e deixaria mapas sem sentido.

**Escolha.** Quando os dois lados usam a mesma instância de repositório, os handlers mantêm as referências sem clones e sem linhas de mapa; só removem, com aviso, o que o destino não consegue gravar, como o embed de um widget que não existe mais. Com repositórios diferentes, o conteúdo é copiado para o do destino. A validação exige apenas que o destino tenha um repositório do tipo; repositórios de e-mail e termos de privacidade continuam exigindo a mesma instância. O desfazer ignora o par (X,X), porque a migração nada criou nele, e deixa mapas antigos do par para a exclusão da migração.

**Consequência.** O desfazer não apaga itens de um repositório compartilhado, e trocar o repositório do destino depois de migrar não move o que já foi copiado. A decisão vale só para o serviço hierárquico; o `contentmigration` original não muda.

**Evidência.** `dependency/DependencyContext.isSameRepository`, `validation/DependencyRules` e `undo/MigrationUndoer.repositoryItems`. O comportamento está descrito em `docs/hierarchicalcontentmigration.md`, seção “Repositórios das dependências”.

## CM-D008 — Violações de validação estruturadas

**Contexto.** A mensagem única “As instâncias não atendem aos requisitos” não dizia qual regra falhou, em qual instância nem o que fazer, e a validação parava na primeira falha.

**Escolha.** `DependencyRules.validate` devolve todas as violações como `RuleViolation` (regra, assunto, instâncias, repositórios e workflows), e `RulesViolatedException` e `MigrationApiException` as carregam sem texto localizado. O texto sai de `MigrationProcessActionHandler`, a partir das chaves `STR_MIGRATION_RULE_*` dos arquivos de strings, na língua da sessão, com uma linha por violação e o passo do usuário. A mensagem em inglês da violação serve só ao diagnóstico.

**Consequência.** Quem chama a API recebe a lista estruturada e escolhe como exibi-la; a interface mostra todas as correções de uma vez. Nova regra exige uma chave `STR_MIGRATION_RULE_<REGRA>` nos dois idiomas, senão aparece a mensagem genérica de requisito não atendido.

**Evidência.** `dto/RuleViolation`, `exception/RulesViolatedException`, `processaction/MigrationProcessActionHandler.formatViolation` e a tabela de `docs/hierarchicalcontentmigration.md`, seção “Mensagens de validação”.
