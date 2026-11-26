# Biblioteca GoF

Trabalho de Modelagem de Sistemas Computacionais — PUCPR. Sistema de biblioteca em Java com cadastro, reserva, empréstimo, renovação, devolução e multas, aplicando os dez padrões GoF exigidos.

## Compilar, testar e executar

Requisitos: **JDK 17 ou superior e Maven**. No terminal do VS Code, dentro da pasta que contém `pom.xml`:

```sh
mvn clean verify
```

Esse comando compila, executa os testes e gera o JAR em `target`. A versão corrigida foi validada com **36 testes aprovados, zero falhas e `BUILD SUCCESS`**, incluindo MySQL real. Sem configurar o banco de teste, são executados 35 testes e um teste de integração é marcado como ignorado.

Para abrir o menu:

```sh
mvn -q exec:java -Dexec.mainClass=biblioteca.Main
```

Digite **7** para demonstrar reserva, empréstimo, renovação, devolução atrasada e multa. Use **0** para voltar/sair e `voltar` para cancelar um preenchimento. A troca de política de multa fica em **5 → 2**.

O perfil padrão é `memoria`: os dados são descartados ao encerrar. Para escolhê-lo explicitamente no macOS/Linux, use `BIB_PERFIL=memoria` antes do comando de execução.

Interface web opcional:

```sh
mvn -q exec:java -Dexec.mainClass=biblioteca.Web
```

Abra <http://127.0.0.1:8080> e mantenha o processo em execução. O terminal oferece opções adicionais à interface web.

## MySQL — perfil de produção

1. Com o servidor MySQL funcionando, abra `mysql/configurar.sql` no Workbench, conectado como administrador.
2. Substitua `SENHA_LOCAL_ESCOLHIDA` por uma senha própria e a linha `SOURCE schema.sql;` pelo conteúdo de `schema.sql`. Execute o script: ele cria a base `biblioteca_gof`, as tabelas e o usuário `biblioteca_app`. Não salve a senha real nos arquivos da entrega. Se o usuário já existir, sua senha não será redefinida.
3. No terminal **zsh do macOS**, execute as linhas abaixo. Após a linha `read`, digite a senha e pressione Return antes de continuar; a digitação fica oculta.

```sh
export BIB_PERFIL=producao
export BIB_JDBC='jdbc:mysql://127.0.0.1:3306/biblioteca_gof'
export BIB_USUARIO=biblioteca_app
read -s 'BIB_SENHA?Senha do usuario biblioteca_app: '
export BIB_SENHA
mvn -q exec:java -Dexec.mainClass=biblioteca.Main
```

As variáveis valem para esse terminal; configure-as novamente ao abrir outro. Para voltar à memória, execute `export BIB_PERFIL=memoria`.

Livros, movimentações, multas e grupos são persistidos no perfil MySQL; a fila de inspeção é reconstituída pelos estados salvos dos livros. Indicadores, cache e seleção de política duram a sessão. Bancos A/B e e-mail são simulados. As tabelas e a configuração do banco permanecem as mesmas da versão anterior.

Para executar também o trecho JDBC do R08 e `IntegracaoMySQLTest`, prepare uma **base exclusiva de teste** com `schema.sql`, conceda acesso ao usuário configurado e defina `BIB_TESTE_JDBC` com sua URL antes de `mvn clean verify`. A revisão validou esses testes em MySQL 8.4 com usuário de aplicação. Não use uma base em uso por outras instâncias para os testes.

## Alterações na implementação

- Menu numerado e consultas preservados; nenhuma nova função de negócio foi adicionada.
- State: estados validam as condições de circulação e produzem os resultados; a fachada coordena a gravação transacional.
- Strategy: políticas registradas na configuração; o menu as lista dinamicamente e o serviço de multas não precisa mudar para incluir outra política.
- Exclusão remove referências dos grupos e o vínculo ao último empréstimo, preservando o histórico consultável por UUID. Um novo cadastro com o mesmo ID não herda a multa antiga.
- Grupos persistidos e fila de inspeção conciliada com os livros, inclusive após reinício ou notificações atrasadas. Os testes cobrem as correções e a integração MySQL.

Os diagramas da etapa anterior não foram alterados, conforme a orientação verbal do professor relatada pela equipe. As mudanças principais estão resumidas acima.

## Uso de inteligência artificial

Foi utilizado **ChatGPT, da OpenAI, incluindo o ambiente Codex**, na geração e revisão de código Java, testes, interface web, configuração de persistência, interpretação dos requisitos e documentação.

**Modelos:** as versões exatas utilizadas nas diferentes etapas ainda não foram confirmadas e devem ser identificadas pela equipe antes da entrega.

Os pedidos à IA e as decisões de aceitar, corrigir ou descartar estão em [REGISTRO_LLM.md](REGISTRO_LLM.md). A equipe é responsável por revisar e explicar o código apresentado.

A relação entre requisitos, padrões, classes e testes está em [RASTREABILIDADE.md](RASTREABILIDADE.md).
