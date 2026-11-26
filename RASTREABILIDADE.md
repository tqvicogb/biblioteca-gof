# Rastreabilidade dos requisitos

A tabela relaciona cada requisito obrigatório ao padrão, às classes que desempenham seus papéis e ao teste correspondente. Os dez testes R01–R10 estão em `src/test/java/biblioteca/CenariosTest.java`.

| Requisito | Padrão | Classes e papéis | Teste |
|---|---|---|---|
| R01 | State | `Livro` é o contexto, `Estado` é o contrato, e `Livre`, `Reservado`, `Emprestado`, `Renovado`, `Devolvido`, `Reparando` e `Incinerado` são estados concretos. | `R01_terceiraRenovacaoRecusada` |
| R02 | Strategy | `TratadorDeMulta` usa `PoliticaMulta`. As políticas concretas são `PoliticaOriginal`, `PoliticaComTeto` e `PoliticaComTolerancia`. `ConfiguracaoMulta` registra e seleciona as políticas; o menu consulta as opções pelo tratador. | `R02_politicasDeMulta` |
| R03 | Adapter | `iCobranca` é o contrato alvo, `BancoA` e `BancoB` são os serviços adaptados, e `AdaptadorBancoA` e `AdaptadorBancoB` convertem pedidos e avisos. `TratadorDeMulta` recebe a confirmação por `iQuitacao`. | `R03_adaptaValoresEImpedeQuitacaoDuplicada` |
| R04 | Facade | `FachadaCirculacao` implementa `iCirculacao`, é chamada pelos tratadores e coordena `Relacionamento`, `Movimentacao`, `Acervo` e `Livro` dentro da `UnidadeDeTrabalho`. | `R04_fachadaEfetivaEmprestimoEmUmaTransacao` |
| R05 | Observer | `PublicadorDevolucao` envia `Devolucao` a `OuvinteDevolucao`. Ouvintes concretos: `OuvinteInspecao`, `OuvinteMulta`, `OuvinteIndicadores`, `OuvinteEmail`. | `R05_publicaDepoisDeRegistrarEIsolaFalhas` |
| R06 | Command | `Main` cria `SolicitarReserva` e `SolicitarRenovacao`, implementações de `SolicitacaoCirculacao`. `ExecutorSolicitacoes` invoca o comando, que aciona `iCirculacao`. `RegistroSolicitacao` guarda o resultado. | `R06_mesmoIdentificadorMesmoResultadoEmConcorrencia` |
| R07 | Template Method | `RotinaCadastro.incluir` define a ordem. Os tratadores de Acervo, Pessoa, Estudante e Atendente implementam as etapas e os contratos de cada componente. | `R07_duplicadoEPreRequisitoDeEstudante` |
| R08 | Abstract Factory | `FabricaPersistencia` cria os produtos `UnidadeDeTrabalho` e `Repositorio`. `FabricaEmMemoria` e `FabricaMySQL` criam as famílias concretas correspondentes. | `R08_perfisCriamFamiliasCompativeis` |
| R09 | Decorator | `ConsultaAcervo` é o componente, `ConsultaOriginal` é o componente concreto, `ConsultaComCache` e `ConsultaComMedicao` são os decoradores. `FachadaCirculacao` lê diretamente o acervo. | `R09_cacheMedeAcertosEMantemEstadoAutoritativo` |
| R10 | Composite | `NoAcervo` é o componente, `GrupoAcervo` agrega nós e `ItemAcervo` é a folha que referencia um livro. `OrganizacaoAcervo` grava e reconstitui a árvore usando a família de persistência selecionada. | `R10_contaCincoEMoveSemAlterarEmprestimo` |

## Testes complementares e execução

- `RevisaoTest.java`: 14 testes de regressão do menu e das regras.
- `AlinhamentoTest.java`: 11 testes de State, extensão de Strategy, exclusão/recadastro, transações, grupos e inspeções.
- `IntegracaoMySQLTest.java`: fluxo com MySQL real, reabertura por novas instâncias, persistência dos grupos e conciliação da fila, quitação repetida e preservação do histórico após exclusão.

Os arquivos estão em `src/test/java/biblioteca/`. Execute:

```sh
mvn clean verify
```

A versão corrigida passou nos **36 testes com MySQL real**, sem falhas. Sem `BIB_TESTE_JDBC`, 35 testes são executados e `IntegracaoMySQLTest` é ignorado; o R08 verifica a composição das famílias sem abrir conexão. As instruções para habilitar o banco de teste estão no README.
