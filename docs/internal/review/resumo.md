# Resumo do review do serviço de migração

## Escopo revisado

O review cobre seis áreas do módulo `br.com.lumis.commons.contentmigrator.service.contentmigration`: núcleo e persistência; cópia e desfazer; mídia e documento; SPI, categorização, tags e widgets; adaptadores Lumis; testes e harness. Os relatórios por área registram os arquivos, impactos, correções, testes e perguntas em aberto: [núcleo](nucleo.md), [cópia e desfazer](copia-desfazer.md), [mídia e documento](midia-documento.md), [SPI, categorização e widget](spi-categorizacao-widget.md), [adaptadores](adaptadores.md) e [testes](testes.md).

O review corrigiu defeitos observáveis sem ampliar o contrato público de `MigrationApi`. Os itens adiados dependem de decisão de produto ou de ambientes que não estão disponíveis na validação local.

## Achados corrigidos

| Área | Achados corrigidos | Achados adiados | Risco restante |
| --- | --- | --- | --- |
| Núcleo e persistência | Validação de `updateMode`, datas portáveis no MySQL, fallback de configurações inválidas, ordem de locks, preservação da exceção original e clareza do desfazer. | Estado de migração após perda do lock, uso de `MigRepoMap`, limite do nome, fonte do relógio e contagem de linhas no MySQL. | Uma configuração alterada depois da criação pode deixar clones antigos fora do desfazer; a suíte local não cobre Oracle nem SQL Server. |
| Cópia e desfazer | Workflow da fonte, locale de fontes sem idioma, público e destinos de publicação, consulta de referência, slug e contratos documentados. | Histórico criado em cada `UPDATE`, origem apagada durante a execução, referências para itens futuros, lookup entre fontes, revalidação no início e aviso de publicação social. | `UPDATE` pode criar versões sem mudança na origem; algumas referências podem continuar vazias quando o alvo ainda não foi migrado. |
| Mídia e documento | Links relativos, ausência de repositório, escolha determinística de pasta, consulta de referência, troca de website no documento, caminhos malformados e aviso de transformação. | Referências apagadas, host de mídia entre websites, decisão de exclusão pelo mapa compartilhado e cópias repetidas em `UPDATE`. | Cenários de websites distintos e alguns casos de reuso/remoção de arquivos não têm fixture local. |
| SPI, categorização, tags e widget | Embed órfão, repositório de categorização ausente, locale de termos, consulta de referência, escopo do desfazer e atributos com aspas simples. | Termo sem versão publicada e dependência removida após a criação. | A execução pode parar com erro de plataforma se a configuração de categoria mudar ou o termo não tiver versão publicada. |
| Adaptadores Lumis | Permissões verificadas antes de expor o estado do mapeamento de widget e traduções pt-BR alinhadas às chaves em inglês. | Paginação de listas e uniformidade das respostas REST quando não há migrações visíveis. | Listas grandes podem consumir memória; o `403` atual permite inferir que há migrações globais. |
| Testes e harness | Limpeza falha de forma verificável, fixtures usam identificadores com entropia suficiente, resolução reflexiva rejeita ambiguidades e o runner restaura o módulo instalado após deploy incompleto. | Bancos alternativos, topologias multi-website, integração social, carga e falha injetada no fechamento de transação. | A regressão local usa MySQL e Elasticsearch; os cenários adiados dependem de ambiente ou contrato ainda inexistente. |

## Achados adiados

As pendências que alteram comportamento de produto ficam agrupadas nas decisões abaixo, a cargo de quem adota o módulo. Até que sejam decididas, o serviço mantém os comportamentos documentados nos relatórios individuais; este fechamento não altera API, esquema de dados, permissões nem rotas.

## Decisões e informações necessárias na adoção

1. Definir como encerrar uma migração antiga quando outra execução assume um lock vencido, inclusive se `stop` e `delete` podem operar sem o lock.
2. Definir se a criação deve congelar as dependências ou se o início deve revalidá-las; decidir se `MigRepoMap` será usada no desfazer/exclusão e como limpar clones após troca de repositório.
3. Escolher se `UPDATE` deve criar versões sem mudança e estabelecer a política para origem apagada, referências a itens ainda não migrados e lookups entre fontes.
4. Definir as regras de exclusão e reuso de mídia/documentos: item apagado, host de mídia em outro website, clones compartilhados e cópias repetidas.
5. Decidir se termos sem versão publicada usam a versão ativa, são omitidos com aviso ou bloqueiam o item; definir também o aviso de publicação social.
6. Informar os volumes esperados de migrações e widgets para escolher retenção, paginação e busca; decidir se respostas REST sem acesso devem ser uniformes.
7. Definir o limite de nome e a fonte temporal canônica; manter ou remover a dependência de `useAffectedRows` na configuração MySQL.
8. Antes de declarar compatibilidade operacional nos três bancos, executar a regressão em Oracle e SQL Server e validar os cenários de website e integração que não existem no ambiente local.

## Verificações executadas

Os rótulos `VAL-*` nomeiam as verificações desta rodada de revisão; quando o comando importa, ele aparece entre parênteses.

- `VAL-COMPILE`: compilação limpa do módulo e dos testes passou com Java 21.
- `VAL-STYLE`: 97 arquivos verificados, sem violações.
- `VAL-REGRESSION`: passou; 73 testes, zero falhas, com MySQL e Elasticsearch saudáveis. O Tomcat permaneceu parado.
- `VAL-STRUCTURE-TESTS`: passou com zero erros e zero avisos.
- `VAL-DOCS` e `VAL-GUIDE`: passaram; seções e referências do README e do guia estão válidas.
- `VAL-REVIEW`: passou após a atualização deste resumo.
