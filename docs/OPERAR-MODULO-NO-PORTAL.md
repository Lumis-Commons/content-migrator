# Operar o Content Migrator no Portal

Procedimentos a partir da raiz do módulo. As decisões CM-D001 a CM-D004 e CM-D006 estão em `docs/DECISOES-TECNICAS.md`. Este guia documenta operações; sua leitura não executa instalação, remoção ou SQL.

## Ferramentas e estado do ambiente

As operações usam as ferramentas `ai_tools` do ambiente de desenvolvimento LumisXP 19.0.0, externas a este módulo. Leia o roteador externo e, para atualização, seu documento `MODULES.md`:

```sh
export AI_TOOLS=<AI_TOOLS_HOME>
```

`<AI_TOOLS_HOME>` é a pasta dessas ferramentas na máquina. O harness de `test/` usa a mesma pasta pela variável `LUMIS_AI_TOOLS`; sem ela, procura `ai_lumis_dev/ai_tools` na pasta da versão do ambiente, oito níveis acima da raiz do módulo. O roteador fica em `$AI_TOOLS/README.md`. Configuração da máquina e autenticação permanecem fora deste módulo. Não copie seus valores para comandos versionados ou evidências.

Antes de operar, confirme o ambiente 19.0.0, o banco selecionado e o estado dos servidores, sem outro harness em execução. A JVM de atualização do Portal precisa de Elasticsearch disponível e Tomcat parado. A opção `--restart` cuida da parada e restaura o Tomcat se ele estava ativo. Não rode outra JVM Portal sobre o mesmo banco durante a operação.

Consultas sem inicializar uma JVM Portal:

```sh
"$AI_TOOLS/update-module.sh" which src/META-INF/lumis-module.xml
"$AI_TOOLS/tomcat.sh" status
"$AI_TOOLS/update-module.sh" --dry-run --restart br.com.lumis.commons.contentmigrator
```

Use `which` para conferir o ID; `--dry-run` apenas apresenta o plano. `--list` lista módulos e último deploy, conforme `MODULES.md`, respeitando seus requisitos de execução.

## Instalar e registrar componentes

1. Disponibilize esta pasta como fonte editável no ambiente. No Gerenciador de Módulos do Portal, cadastre ou abra a fonte com `src` como raiz quando usar o perfil local abaixo. Confirme o descritor `src/META-INF/lumis-module.xml` e o ID retornado por `which`; nome da pasta Maven não substitui ID de deploy.
2. Compile e atualize pela sequência da próxima seção. Verifique o resultado do módulo e dos componentes, inclusive avisos. Não interprete a compilação como instalação concluída.
3. No gerenciamento de componentes, instale os componentes dos serviços necessários: `br.com.lumis.commons.contentmigrator.service.contentmigration` e/ou `br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration`. Confirme seu estado instalado antes de criar instâncias. Se já foram instalados pelo fluxo de deploy, confira o estado em vez de repetir o setup.
4. Cada serviço executa seu `componentfiles/setup/component-install-script.xml`, que aplica `databasedefinition.xml`. Confira as tabelas do serviço no banco correto. A relação para conferência está na seção “Desinstalar e limpar”.
5. Crie as instâncias e interfaces no canal escolhido, configure permissões e valide o acesso. A estrutura do módulo é um componente separado; só sincronize estrutura quando a alteração exigir esse efeito no Portal.

Instalação nova não importa histórico de uma instalação anterior. O limite está definido em CM-D002. Não renomeie tabelas existentes para simular instalação.

## Compilar e publicar

Para raiz aberta em `src`, compile explicitamente antes do redeploy:

```sh
mvn -o -B -f pom.xml -Plocal-development compile
"$AI_TOOLS/update-module.sh" --restart br.com.lumis.commons.contentmigrator
```

O perfil grava classes junto das fontes. A compilação padrão `mvn -o -B -f pom.xml compile` usa `target/classes`; escolha conforme a raiz registrada. O pré-deploy copia recursos conforme `predeploy-ant.xml`. Depois da atualização, confira erros/avisos e, se o Tomcat estiver ativo, execute:

```sh
"$AI_TOOLS/tomcat.sh" health
```

Quando a mudança exigir processar a estrutura instalada, use na atualização:

```sh
"$AI_TOOLS/update-module.sh" --restart --sync-structure br.com.lumis.commons.contentmigrator
```

Essa opção pode modificar a estrutura do Portal; não é necessária para uma alteração apenas documental. Para automação por agente, siga também as instruções de deployment do `LUMIS-AI-INSTRUCTIONS.md` do pacote de referência de IA do LumisXP (`../../data/lumis-ai-reference/` a partir da raiz do módulo instalado em `lumisdata/shared/source/`), usando o Lumis MCP quando disponível. A ausência dessa ferramenta deve ser reportada, sem substituí-la por execução de terminal fora da autorização da tarefa.

### Pendência do ai_tools

Na cópia consultada, `$AI_TOOLS/UpdateRunner.java:1964`, método `mavenCommand`, ativa `-Plocal-development` apenas para o ID de outro módulo. Para este módulo, informar Java ou `--build` dispara uma compilação sem esse perfil. Até a ferramenta suportar este ID, mantenha a compilação explícita acima e atualize pelo ID depois dela. Não pressuponha que `--build` atualizou as classes em `src`. Esta pendência pertence à ferramenta externa; não é corrigida no módulo.

### Clean e classes locais antigas

```sh
mvn -o -B -f pom.xml -Plocal-development clean
```

Esse `clean` limpa a saída de build configurada no POM, preservando fontes e recursos em `src`; ele não remove classes locais obsoletas. Ao mover, renomear ou excluir Java, liste primeiro somente as classes locais:

```sh
find src -type f -name '*.class' -print
```

Depois de conferir a lista, remova as classes antes de recompilar todas as fontes locais:

```sh
find src -type f -name '*.class' -delete
mvn -o -B -f pom.xml -Plocal-development compile
```

Não use `rm -rf src`, não configure o clean para apagar essa pasta e não inclua fontes ou recursos na remoção. Faça a recompilação e o redeploy antes de iniciar uma validação funcional.

## Desinstalar e limpar

1. Interrompa novas migrações e aguarde o encerramento das execuções. Preserve backup e histórico que precisem ser mantidos antes da remoção.
2. Remova pelo Portal as interfaces e instâncias que dependem dos serviços e trate a estrutura instalada. Apagar arquivos do disco ou atualizar o módulo não desfaz essas instalações.
3. Desinstale os componentes dos serviços pelo gerenciamento de componentes. Os scripts de desinstalação removem as tabelas de controle e o histórico de migrações. Essa remoção não executa “desfazer migração” nem apaga automaticamente os conteúdos copiados nos serviços de destino.
4. Confira o resultado e as tabelas abaixo. Remova o registro do módulo no Gerenciador de Módulos somente após tratar os componentes e dependências. Exclua a fonte do disco apenas se também quiser encerrar seu desenvolvimento.

Os nomes e a ordem abaixo vêm dos `component-uninstall-script.xml` dos serviços. Para conferência ou recuperação manual de uma desinstalação parcial, confira o banco e execute somente os comandos correspondentes às tabelas remanescentes, com o Portal parado e sem harness. Não execute o bloco inteiro em uma instalação ativa nem suprima erros de dependência sem examiná-los.

```sql
-- Serviço contentmigration
DROP TABLE lum_cmig_MigLock;
DROP TABLE lum_cmig_MigWidgetMap;
DROP TABLE lum_cmig_MigRepoMap;
DROP TABLE lum_cmig_MigItemMap;
DROP TABLE lum_cmig_Migration;

-- Serviço hierarchicalcontentmigration
DROP TABLE lum_cmig_HMigSelection;
DROP TABLE lum_cmig_HMigLock;
DROP TABLE lum_cmig_HMigWidgetMap;
DROP TABLE lum_cmig_HMigRepoMap;
DROP TABLE lum_cmig_HMigItemMap;
DROP TABLE lum_cmig_HMigration;
```

Não remova tabelas nativas do Portal ou de outra instalação. Para apenas limpar dados de testes, use as fixtures e a limpeza do harness descritas em `test/AGENTS.md`; desinstalação não é rotina de limpeza de teste. Se for necessário desfazer conteúdo migrado, use o fluxo funcional de desfazer enquanto o histórico ainda existir, antes de desinstalar.

## Conferir o resultado e diagnosticar

Registre ID do módulo, componentes afetados e resultado, sem credenciais ou dados de conteúdo. O ai_tools aponta os logs de compilação em `$AI_TOOLS/logs/maven-last.log` e de deploy em `$AI_TOOLS/logs/update-module-last.log`. Erros completos do Portal ficam em `portal_install/lumisdata/log/full/logfull.log`, relativo à instalação; esses caminhos são externos à raiz do módulo.

Se houver falha, confira a etapa indicada e o estado dos componentes antes de repetir. Não trate um redeploy como correção de setup parcial nem limpe tabelas para ocultar erro. Testes funcionais usam o harness de `test/AGENTS.md`, que administra sua própria JVM e o ciclo do Tomcat.
