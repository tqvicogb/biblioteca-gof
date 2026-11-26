package biblioteca;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.function.Consumer;

/** Interface de apresentação: toda operação de negócio passa por um tratador. */
public final class Main {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private final TratadorDeAcervo livros;
    private final TratadorDePessoa pessoas;
    private final TratadorDeEstudante estudantes;
    private final TratadorDeAtendente atendentes;
    private final TratadorDeEmprestimo emprestimos;
    private final TratadorDeDevolucao devolucoes;
    private final TratadorDeMulta multas;
    private final TratadorDaOrganizacao organizacao;
    private final TratadorDeIndicadores relatorios;
    private final Scanner entrada;
    private final PrintStream saida;
    private SolicitacaoCirculacao ultimaSolicitacao;
    private UUID ultimoPagamento;
    private String ultimoProtocolo;

    Main(Biblioteca biblioteca, Scanner entrada, PrintStream saida) {
        livros = biblioteca.livros;
        pessoas = biblioteca.pessoas;
        estudantes = biblioteca.estudantes;
        atendentes = biblioteca.atendentes;
        emprestimos = biblioteca.emprestimos;
        devolucoes = biblioteca.devolucoes;
        multas = biblioteca.multas;
        organizacao = biblioteca.organizacao;
        relatorios = biblioteca.relatorios;
        this.entrada = entrada;
        this.saida = saida;
    }

    public static void main(String[] args) {
        try (Scanner entrada = new Scanner(System.in)) {
            var config = System.getenv();
            Main cli = new Main(Biblioteca.configurar(config), entrada, System.out);
            if (args.length > 0 && args[0].equals("demo")) { cli.demo(); return; }
            if (args.length > 0 && args[0].equals("comandos")) { cli.comandos(); return; }
            System.out.println("Perfil: " + config.getOrDefault("BIB_PERFIL", "memoria"));
            if (config.getOrDefault("BIB_PERFIL", "memoria").equals("memoria")) {
                System.out.println("Os dados desta sessão são apagados ao encerrar.");
            }
            cli.executarMenu();
        } catch (FimEntrada fim) {
            System.out.println("Entrada encerrada. Até logo!");
        }
    }

    void executarMenu() {
        saida.println("Digite voltar em qualquer pergunta para cancelar a operação.");
        menu("BIBLIOTECA — MENU PRINCIPAL", """
                1 — Cadastros
                2 — Consultas
                3 — Reserva, empréstimo e renovação
                4 — Devolução, inspeção e reparo
                5 — Multas e políticas de cobrança
                6 — Organização do acervo
                7 — Demonstração automática
                0 — Sair""", opcao -> {
            switch (opcao) {
                case "1" -> cadastros();
                case "2" -> consultas();
                case "3" -> circulacao();
                case "4" -> inspecoes();
                case "5" -> financeiro();
                case "6" -> acervo();
                case "7" -> demo();
                default -> opcaoInvalida();
            }
        });
        saida.println("Biblioteca encerrada.");
    }

    private void cadastros() {
        menu("CADASTROS", """
                1 — Cadastrar pessoa
                2 — Cadastrar estudante
                3 — Cadastrar atendente
                4 — Cadastrar livro
                5 — Excluir livro livre
                0 — Voltar""", opcao -> {
            switch (opcao) {
                case "1" -> mostrar(pessoas.incluir(new Pessoa(perguntar("CPF"), perguntar("Nome"), perguntar("E-mail"))));
                case "2" -> mostrar(estudantes.incluir(new Estudante(perguntar("ID do estudante"), perguntar("CPF da pessoa cadastrada"))));
                case "3" -> mostrar(atendentes.incluir(new Atendente(perguntar("ID do atendente"), perguntar("CPF da pessoa cadastrada"))));
                case "4" -> mostrar(livros.incluir(new Livro(perguntar("ID do livro"), perguntar("Título"))));
                case "5" -> {
                    String id = perguntar("ID do livro");
                    if (confirmar("Excluir o cadastro " + id + "?")) {
                        livros.excluir(id);
                        saida.println("Livro excluído.");
                    }
                }
                default -> opcaoInvalida();
            }
        });
    }

    private void consultas() {
        menu("CONSULTAS", """
                1 — Situação completa do livro
                2 — Consultar título e identificação
                3 — Consultar reserva de um estudante
                4 — Consultar pessoa por CPF
                5 — Consultar estudante
                6 — Consultar atendente
                0 — Voltar""", opcao -> {
            switch (opcao) {
                case "1" -> mostrar(emprestimos.consultarSituacao(perguntar("ID do livro")));
                case "2" -> mostrar(livros.consultar(perguntar("ID do livro")));
                case "3" -> mostrar(emprestimos.consultarReserva(perguntar("ID do livro"), perguntar("ID do estudante")));
                case "4" -> mostrar(pessoas.consultar(perguntar("CPF")));
                case "5" -> mostrar(estudantes.consultar(perguntar("ID do estudante")));
                case "6" -> mostrar(atendentes.consultar(perguntar("ID do atendente")));
                default -> opcaoInvalida();
            }
        });
    }

    private void circulacao() {
        menu("CIRCULAÇÃO", """
                1 — Reservar livro
                2 — Efetivar empréstimo
                3 — Renovar empréstimo
                4 — Repetir última solicitação de reserva ou renovação
                5 — Consultar situação do livro
                0 — Voltar""", opcao -> {
            switch (opcao) {
                case "1", "3" -> {
                    String livro = perguntar("ID do livro");
                    String estudante = perguntar("ID do estudante");
                    ultimaSolicitacao = opcao.equals("1")
                            ? new SolicitarReserva(UUID.randomUUID(), estudante, Instant.now(), livro)
                            : new SolicitarRenovacao(UUID.randomUUID(), estudante, Instant.now(), livro);
                    mostrar(emprestimos.solicitar(ultimaSolicitacao));
                }
                case "2" -> mostrar(emprestimos.emprestar(perguntar("ID do livro"), perguntar("ID do estudante"),
                        data(perguntar("Vencimento (DD/MM/AAAA)"))));
                case "4" -> {
                    if (ultimaSolicitacao == null) throw new IllegalStateException("Nenhuma solicitação feita nesta sessão.");
                    saida.println("Reenviando o mesmo pedido para o livro " + ultimaSolicitacao.livro() + ".");
                    mostrar(emprestimos.solicitar(ultimaSolicitacao));
                    saida.println("Situação atual (o comprovante repetido pode ser de uma operação anterior):");
                    mostrar(emprestimos.consultarSituacao(ultimaSolicitacao.livro()));
                }
                case "5" -> mostrar(emprestimos.consultarSituacao(perguntar("ID do livro")));
                default -> opcaoInvalida();
            }
        });
    }

    private void inspecoes() {
        menu("DEVOLUÇÃO E INSPEÇÃO", """
                1 — Registrar devolução
                2 — Inspecionar livro: bom estado
                3 — Inspecionar livro: danificado
                4 — Enviar livro livre para reparo
                5 — Registrar retorno do reparo
                6 — Registrar laudo irrecuperável
                7 — Consultar fila de inspeção pendente
                8 — Consultar indicadores e falhas desta sessão
                0 — Voltar""", opcao -> {
            switch (opcao) {
                case "1" -> mostrar(devolucoes.devolver(perguntar("ID do livro"), perguntar("ID do estudante"),
                        data(perguntar("Data da devolução (DD/MM/AAAA)"))));
                case "2", "3" -> mostrar(devolucoes.inspecionar(perguntar("ID do livro"), opcao.equals("3")));
                case "4" -> mostrar(devolucoes.enviarParaReparo(perguntar("ID do livro")));
                case "5" -> mostrar(devolucoes.retornarDoReparo(perguntar("ID do livro")));
                case "6" -> {
                    String livro = perguntar("ID do livro");
                    if (confirmar("Declarar " + livro + " irrecuperável?")) mostrar(devolucoes.laudoIrrecuperavel(livro));
                }
                case "7" -> mostrarLista(devolucoes.pendentes(), "Nenhum livro na fila de inspeção.");
                case "8" -> {
                    ResumoDevolucoes resumo = relatorios.consultar();
                    saida.println("Devoluções nesta sessão: " + resumo.total());
                    mostrarLista(resumo.falhas(), "Nenhuma falha de ouvinte registrada nesta sessão.");
                }
                default -> opcaoInvalida();
            }
        });
    }

    private void financeiro() {
        menu("MULTAS E POLÍTICAS", """
                1 — Consultar política vigente
                2 — Alterar política de multa
                3 — Consultar multa do último empréstimo de um livro
                4 — Consultar multa pelo identificador do empréstimo
                5 — Simular pagamento da multa de um livro
                6 — Repetir última notificação de pagamento
                0 — Voltar""", opcao -> {
            switch (opcao) {
                case "1" -> saida.println("Política vigente: " + multas.politicaVigente());
                case "2" -> {
                    List<OpcaoPolitica> opcoes = multas.politicasDisponiveis();
                    for (int i = 0; i < opcoes.size(); i++) saida.println((i + 1) + " — " + opcoes.get(i).descricao());
                    int numero = Integer.parseInt(perguntar("Política"));
                    if (numero < 1 || numero > opcoes.size()) throw new IllegalArgumentException("Escolha uma política da lista.");
                    multas.alterarPolitica(opcoes.get(numero - 1).chave());
                    saida.println("Política vigente: " + multas.politicaVigente());
                    saida.println("Multas já emitidas mantêm o valor e a política originais.");
                }
                case "3" -> mostrarMulta(multas.consultarPorLivro(perguntar("ID do livro")));
                case "4" -> mostrarMulta(multas.consultar(UUID.fromString(perguntar("Identificador do empréstimo"))));
                case "5" -> {
                    BoletoDeMulta boleto = multas.consultarPorLivro(perguntar("ID do livro"));
                    if (boleto == null) throw new IllegalStateException("Nenhuma multa encontrada no último empréstimo desse livro.");
                    ultimoPagamento = boleto.emprestimo();
                    ultimoProtocolo = Long.toString(System.currentTimeMillis());
                    pagamento();
                }
                case "6" -> {
                    if (ultimoPagamento == null) throw new IllegalStateException("Nenhum pagamento simulado nesta sessão.");
                    pagamento();
                }
                default -> opcaoInvalida();
            }
        });
    }

    private void pagamento() {
        boolean novo = multas.simularPagamento(ultimoPagamento, ultimoProtocolo);
        saida.println(novo ? "Pagamento simulado registrado." : "Pagamento já registrado. Nenhuma segunda quitação.");
        mostrarMulta(multas.consultar(ultimoPagamento));
    }

    private void acervo() {
        menu("ORGANIZAÇÃO DO ACERVO", """
                1 — Criar unidade, seção ou subseção
                2 — Incluir livro em um grupo
                3 — Mover livro entre grupos
                4 — Listar e contar livros de um grupo
                5 — Mostrar hierarquia
                0 — Voltar""", opcao -> {
            switch (opcao) {
                case "1" -> {
                    organizacao.criarGrupo(perguntar("Grupo pai (use raiz para o primeiro nível)"), perguntar("ID do novo grupo"));
                    saida.println("Grupo criado.");
                }
                case "2" -> {
                    organizacao.incluirLivro(perguntar("Grupo de destino"), perguntar("ID do livro"));
                    saida.println("Livro incluído no grupo.");
                }
                case "3" -> {
                    organizacao.mover(perguntar("Grupo de origem"), perguntar("Grupo de destino"), perguntar("ID do livro"));
                    saida.println("Livro movido. Dados do livro e do empréstimo preservados.");
                }
                case "4" -> {
                    String grupo = perguntar("ID do grupo (ou raiz)");
                    saida.println("Total de livros: " + organizacao.contar(grupo));
                    mostrarLista(organizacao.listar(grupo), "Grupo vazio.");
                }
                case "5" -> {
                    for (EntradaAcervo item : organizacao.hierarquia()) {
                        saida.println("  ".repeat(item.nivel()) + (item.grupo()
                                ? "Grupo: " + item.nome() + " (" + item.quantidade() + " livros)"
                                : "Livro: " + item.nome()));
                    }
                }
                default -> opcaoInvalida();
            }
        });
    }

    private void menu(String titulo, String opcoes, Consumer<String> acao) {
        while (true) {
            saida.println("\n" + titulo);
            saida.println(opcoes);
            String opcao = ler("Escolha");
            if (opcao.equals("0") || opcao.equalsIgnoreCase("voltar") || opcao.equalsIgnoreCase("sair")) return;
            try { acao.accept(opcao); }
            catch (Cancelar cancelado) { saida.println("Operação cancelada."); }
            catch (IllegalArgumentException | IllegalStateException erro) {
                saida.println("Operação recusada: " + erro.getMessage());
            }
        }
    }

    private String ler(String rotulo) {
        saida.print(rotulo + ": ");
        saida.flush();
        if (!entrada.hasNextLine()) throw new FimEntrada();
        return entrada.nextLine().strip();
    }
    private String perguntar(String rotulo) {
        String texto = ler(rotulo);
        if (texto.equalsIgnoreCase("voltar")) throw new Cancelar();
        if (texto.isBlank()) throw new IllegalArgumentException("Preencha o campo " + rotulo + ".");
        return texto;
    }
    private boolean confirmar(String pergunta) {
        String resposta = perguntar(pergunta + " (s/n)");
        if (resposta.equalsIgnoreCase("s")) return true;
        if (resposta.equalsIgnoreCase("n")) { saida.println("Operação cancelada."); return false; }
        throw new IllegalArgumentException("Responda s ou n.");
    }
    private void opcaoInvalida() { throw new IllegalArgumentException("Escolha uma opção do menu."); }
    private LocalDate data(String texto) {
        try { return texto.contains("/") ? LocalDate.parse(texto, DATA) : LocalDate.parse(texto); }
        catch (DateTimeParseException erro) { throw new IllegalArgumentException("Data inválida. Use DD/MM/AAAA ou AAAA-MM-DD."); }
    }
    private boolean laudo(String texto) {
        return switch (texto) {
            case "bom" -> false;
            case "danificado" -> true;
            default -> throw new IllegalArgumentException("Informe bom ou danificado.");
        };
    }
    private void mostrarLista(List<String> itens, String vazio) {
        if (itens.isEmpty()) saida.println(vazio);
        else itens.forEach(item -> saida.println("• " + item));
    }
    private void mostrarMulta(BoletoDeMulta boleto) {
        if (boleto == null) {
            saida.println("Nenhuma multa emitida para o empréstimo consultado.");
            return;
        }
        saida.println("Valor: R$ " + boleto.valor().toPlainString().replace('.', ','));
        saida.println("Política aplicada: " + boleto.politica());
        saida.println("Situação: " + boleto.cobranca().situacao());
        saida.println("Referência bancária: " + boleto.cobranca().referencia());
    }
    private void mostrar(Object resultado) {
        if (resultado == null) { saida.println("Registro não encontrado."); return; }
        if (resultado instanceof ResultadoCadastro<?> cadastro) {
            saida.println(cadastro.existente() ? "Cadastro já existente. Dados preservados." : "Cadastro realizado.");
            mostrar(cadastro.cadastro());
        } else if (resultado instanceof Livro l) {
            saida.println("Livro: " + l.id() + " — " + l.titulo());
            saida.println("Estado: " + l.estado().codigo());
        } else if (resultado instanceof Pessoa p) {
            saida.println("Pessoa: " + p.nome() + " | CPF: " + p.cpf() + " | E-mail: " + p.email());
        } else if (resultado instanceof Estudante e) {
            saida.println("Estudante: " + e.id() + " | CPF: " + e.cpf());
        } else if (resultado instanceof Atendente a) {
            saida.println("Atendente: " + a.id() + " | CPF: " + a.cpf());
        } else if (resultado instanceof Metadados m) {
            saida.println("Livro: " + m.id() + " — " + m.titulo());
        } else if (resultado instanceof Reserva r) {
            saida.println("Reserva ativa | Livro: " + r.livro() + " | Estudante: " + r.estudante());
        } else if (resultado instanceof Comprovante c) {
            saida.println("Operação confirmada: " + c.operacao());
            saida.println("Estado do livro: " + c.estado());
            if (c.vencimento() != null) saida.println("Vencimento: " + DATA.format(c.vencimento()));
            if (c.operacao().equals("empréstimo")) saida.println("Identificador do empréstimo: " + c.id());
        } else if (resultado instanceof BoletoDeMulta b) {
            mostrarMulta(b);
        } else if (resultado instanceof SituacaoLivro s) {
            saida.println("Livro: " + s.id() + " — " + s.titulo());
            saida.println("Estado atual: " + s.estado());
            saida.println("Reserva: " + (s.reserva() == null ? "nenhuma" : "estudante " + s.reserva().estudante()));
            if (s.emprestimo() == null) saida.println("Empréstimo ativo: nenhum");
            else {
                saida.println("Estudante: " + s.emprestimo().estudante());
                saida.println("Vencimento: " + DATA.format(s.emprestimo().vencimento()));
                saida.println("Renovações realizadas: " + s.emprestimo().renovacoes());
            }
            if (s.ultimoEmprestimo() != null) saida.println("Último empréstimo: " + s.ultimoEmprestimo());
        } else if (resultado instanceof List<?> lista) {
            if (lista.isEmpty()) saida.println("Nenhum item.");
            else lista.forEach(item -> saida.println("• " + item));
        } else saida.println(resultado);
    }

    private void demo() {
        String livro = "DEMO-" + UUID.randomUUID().toString().substring(0, 8);
        String estudante = "E-demo";
        saida.println("\nDEMONSTRAÇÃO — política vigente: " + multas.politicaVigente());
        pessoas.incluir(new Pessoa("12345678909", "Estudante de exemplo", "demo@example.org"));
        estudantes.incluir(new Estudante(estudante, "12345678909"));
        livros.incluir(new Livro(livro, "Modelagem de Sistemas"));
        saida.println("Livro desta demonstração: " + livro);
        ultimaSolicitacao = new SolicitarReserva(UUID.randomUUID(), estudante, Instant.now(), livro);
        saida.println("\n1. Reserva");
        mostrar(emprestimos.solicitar(ultimaSolicitacao));
        saida.println("\n2. Empréstimo");
        mostrar(emprestimos.emprestar(livro, estudante, LocalDate.of(2026, 10, 18)));
        ultimaSolicitacao = new SolicitarRenovacao(UUID.randomUUID(), estudante, Instant.now(), livro);
        saida.println("\n3. Renovação");
        mostrar(emprestimos.solicitar(ultimaSolicitacao));
        saida.println("\n4. Devolução em 22/11/2026 — cinco dias após o vencimento");
        mostrar(devolucoes.devolver(livro, estudante, LocalDate.of(2026, 11, 22)));
        saida.println("\n5. Multa");
        mostrarMulta(multas.consultarPorLivro(livro));
        saida.println("O livro aguarda inspeção. Consulte-o usando o ID acima.");
    }

    private void comandos() {
        saida.println("Modo de comandos. Digite ajuda ou sair. Separe os campos por |.");
        while (entrada.hasNextLine()) {
            String linha = entrada.nextLine().strip();
            if (linha.equals("sair")) return;
            try { mostrar(executar(linha)); }
            catch (IllegalArgumentException | IllegalStateException erro) {
                saida.println("Operação recusada: " + erro.getMessage());
            }
        }
    }

    private static final class FimEntrada extends RuntimeException {}
    private static final class Cancelar extends RuntimeException {}
    private Object executar(String linha) {
        String[] p = linha.split("\\s*\\|\\s*", -1);
        try {
            return switch (p[0]) {
                case "ajuda" -> """
                        livro|id|título   pessoa|cpf|nome|email   estudante|id|cpf   atendente|id|cpf
                        consulta|livro   excluir|livro   reserva-atual|livro|estudante
                        reservar|livro|estudante|uuid   renovar|livro|estudante|uuid
                        emprestar|livro|estudante|AAAA-MM-DD   devolver|livro|estudante|AAAA-MM-DD
                        inspecionar|livro|bom ou danificado   reparar|livro   retornar|livro   laudo|livro
                        multa|uuid-do-empréstimo   grupo|pai|nome (grupo inicial: raiz)
                        organizar|grupo|livro   mover|origem|destino|livro   listar|grupo
                        demo   sair""";
                case "livro" -> livros.incluir(new Livro(p[1], p[2]));
                case "pessoa" -> pessoas.incluir(new Pessoa(p[1], p[2], p[3]));
                case "estudante" -> estudantes.incluir(new Estudante(p[1], p[2]));
                case "atendente" -> atendentes.incluir(new Atendente(p[1], p[2]));
                case "consulta" -> livros.consultar(p[1]);
                case "excluir" -> { livros.excluir(p[1]); yield "Livro excluído"; }
                case "reserva-atual" -> emprestimos.consultarReserva(p[1], p[2]);
                case "reservar" -> emprestimos.solicitar(new SolicitarReserva(UUID.fromString(p[3]),
                        p[2], Instant.now(), p[1]));
                case "renovar" -> emprestimos.solicitar(new SolicitarRenovacao(UUID.fromString(p[3]),
                        p[2], Instant.now(), p[1]));
                case "emprestar" -> emprestimos.emprestar(p[1], p[2], data(p[3]));
                case "devolver" -> devolucoes.devolver(p[1], p[2], data(p[3]));
                case "inspecionar" -> devolucoes.inspecionar(p[1], laudo(p[2]));
                case "reparar" -> devolucoes.enviarParaReparo(p[1]);
                case "retornar" -> devolucoes.retornarDoReparo(p[1]);
                case "laudo" -> devolucoes.laudoIrrecuperavel(p[1]);
                case "multa" -> multas.consultar(UUID.fromString(p[1]));
                case "grupo" -> { organizacao.criarGrupo(p[1], p[2]); yield "Grupo criado"; }
                case "organizar" -> { organizacao.incluirLivro(p[1], p[2]); yield "Livro organizado"; }
                case "mover" -> { organizacao.mover(p[1], p[2], p[3]); yield "Livro movido"; }
                case "listar" -> organizacao.listar(p[1]);
                case "demo" -> { demo(); yield "Fim da demonstração"; }
                default -> throw new IllegalArgumentException("Comando desconhecido. Digite ajuda.");
            };
        } catch (ArrayIndexOutOfBoundsException erro) {
            throw new IllegalArgumentException("Campos incompletos. Digite ajuda.");
        }
    }

}
