# test — testes do Content Migrator (AGENTS)

Caminhos e comandos partem da raiz do módulo. Java de teste fica em `test/java`; recursos e fixtures ficam aqui, fora de `src/`.

## Rotas

| Quando a tarefa… | Leia |
|---|---|
| altera instruções ou documentação de AGENTS.md | `docs/GUIA-AGENTS-MD.md` |
| altera decisão ou limite de arquitetura | `docs/DECISOES-TECNICAS.md`, seção do assunto |
| configura, publica ou recupera o Portal instalado | `docs/OPERAR-MODULO-NO-PORTAL.md` |

## Regras

- Teste com Portal prova o módulo implantado, carregado pelo Portal; não substitua isso pelo `target/classes` no classpath da JVM de teste.
- Use `bash test/run-tests.sh <ClasseDeTeste>`; com nomes repetidos entre serviços, passe o nome totalmente qualificado.
- Para o serviço hierárquico, use `bash test/run-hier-tests.sh [ClasseDeTeste ...]`; sem argumentos ele roda sua suíte e a verificação final de resíduos.
- `bash test/run-tests.sh --all` executa a suíte geral, exceto o cenário de UI que precisa de configuração própria.
- O harness verifica Elasticsearch, serializa as execuções, para o Tomcat, compila e publica, executa JUnit em JVM Portal própria e restaura o estado inicial do Tomcat ao sair.
- Não contorne o lock, não rode outra JVM Portal sobre o mesmo banco e não reinicie o Tomcat durante o teste.
- Use o ambiente 19.0.0 e suas ferramentas `ai_tools`; não aproveite o banco ou Elasticsearch de outra versão por conveniência.
- Cada teste limpa o que cria mesmo em falha, sem ocultar a falha original; use fixtures e limpeza existentes. Retenção de dados é só para diagnóstico explícito.
- Mantenha testes nos pacotes do serviço correspondente; ao mover código, atualize imports, nomes em texto e referências da ponte no mesmo trabalho.
- Não crie modo de teste no produto, não enfraqueça asserções e não use dados reais como fixture.
- Critério, cobertura e limitações de cada teste ficam no Javadoc; inglês com `@since %LUM_NEXT_VERSION%` em elemento novo ou alterado.
- Verificações estáticas: `bash test/run-static-checks.sh`. Elas não substituem testes funcionais com Portal quando o comportamento muda.
