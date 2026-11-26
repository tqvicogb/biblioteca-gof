package biblioteca;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

record Pessoa(String cpf, String nome, String email) implements Serializable {}
record Estudante(String id, String cpf) implements Serializable {}
record Atendente(String id, String cpf) implements Serializable {}
record Reserva(UUID id, String livro, String estudante) implements Serializable {}
record Emprestimo(UUID id, String livro, String estudante, LocalDate vencimento, int renovacoes)
        implements Serializable {
    Emprestimo renovado() {
        return new Emprestimo(id, livro, estudante, vencimento.plusDays(30), renovacoes + 1);
    }
}
record Devolucao(Emprestimo emprestimo, Estudante estudante, Pessoa pessoa, LocalDate data)
        implements Serializable {}
record Comprovante(UUID id, String operacao, String estado, LocalDate vencimento)
        implements Serializable {}
record SituacaoLivro(String id, String titulo, String estado, Reserva reserva,
                     Emprestimo emprestimo, UUID ultimoEmprestimo) {}

record Acervo(Repositorio<RegistroLivro> repositorio) {
    Livro buscar(String id) {
        RegistroLivro registro = repositorio.buscar(id);
        return registro == null ? null : registro.livro();
    }
    Livro exigir(String id) {
        Livro livro = buscar(id);
        if (livro == null) throw new IllegalArgumentException("Livro não cadastrado: " + id);
        return livro;
    }
    List<String> pendentesDeInspecao() {
        return repositorio.listar().stream().filter(r -> r.livro().aguardaInspecao())
                .map(RegistroLivro::id).sorted().toList();
    }
    void guardar(Livro livro) {
        repositorio.salvar(livro.id(), new RegistroLivro(livro.id(), livro.titulo(), livro.estado().codigo()));
    }
    void excluir(String id) {
        exigir(id).excluir();
        repositorio.remover(id);
    }
}

record Relacionamento(Repositorio<Serializable> repositorio) {
    Pessoa pessoa(String cpf) { return (Pessoa) repositorio.buscar("pessoa:" + cpf); }
    Estudante estudante(String id) { return (Estudante) repositorio.buscar("estudante:" + id); }
    Atendente atendente(String id) { return (Atendente) repositorio.buscar("atendente:" + id); }
    void guardar(Pessoa p) { repositorio.salvar("pessoa:" + p.cpf(), p); }
    void guardar(Estudante e) { repositorio.salvar("estudante:" + e.id(), e); }
    void guardar(Atendente a) { repositorio.salvar("atendente:" + a.id(), a); }

    Estudante exigirEstudante(String id) {
        Estudante estudante = estudante(id);
        if (estudante == null) throw new IllegalArgumentException("Estudante não cadastrado");
        return estudante;
    }
}

record Movimentacao(Repositorio<Serializable> repositorio) {
    Reserva reserva(String livro) { return (Reserva) repositorio.buscar("reserva:" + livro); }
    Emprestimo ativo(String livro) { return (Emprestimo) repositorio.buscar("ativo:" + livro); }
    UUID ultimoEmprestimo(String livro) { return (UUID) repositorio.buscar("ultimo:" + livro); }
    void desvincularLivroExcluido(String livro) { repositorio.remover("ultimo:" + livro); }
    Devolucao devolucao(UUID id) { return (Devolucao) repositorio.buscar("devolucao:" + id); }
    void registrar(Reserva reserva) { repositorio.salvar("reserva:" + reserva.livro(), reserva); }
    void consumirReserva(String livro) { repositorio.remover("reserva:" + livro); }
    void registrar(Emprestimo emprestimo) {
        repositorio.salvar("ativo:" + emprestimo.livro(), emprestimo);
        repositorio.salvar("emprestimo:" + emprestimo.id(), emprestimo);
        repositorio.salvar("ultimo:" + emprestimo.livro(), emprestimo.id());
    }
    void registrar(Devolucao devolucao) {
        repositorio.salvar("devolucao:" + devolucao.emprestimo().id(), devolucao);
        repositorio.remover("ativo:" + devolucao.emprestimo().livro());
    }
}

interface iCirculacao {
    SituacaoLivro consultarSituacao(String livro);
    Comprovante reservar(String livro, String estudante);
    Reserva consultarReserva(String livro, String estudante);
    Comprovante emprestar(String livro, String estudante, LocalDate vencimento);
    Comprovante renovar(String livro, String estudante);
    Comprovante devolver(String livro, String estudante, LocalDate data);
    Comprovante inspecionar(String livro, boolean danificado);
}

record FachadaCirculacao(UnidadeDeTrabalho unidade, Acervo acervo, Movimentacao movimentacao,
                         Relacionamento relacionamento, PublicadorDevolucao publicador)
        implements iCirculacao {
    public SituacaoLivro consultarSituacao(String id) {
        return unidade.executar(() -> {
            Livro livro = acervo.exigir(id);
            return new SituacaoLivro(id, livro.titulo(), livro.estado().codigo(),
                    movimentacao.reserva(id), movimentacao.ativo(id), movimentacao.ultimoEmprestimo(id));
        });
    }
    public Comprovante reservar(String id, String estudante) {
        return unidade.executar(() -> {
            ResultadoReserva resultado = acervo.exigir(id).reservar(relacionamento.estudante(estudante));
            movimentacao.registrar(resultado.reserva());
            acervo.guardar(resultado.livro());
            return comprovante(resultado.reserva().id(), "reserva", resultado.livro(), null);
        });
    }

    public Reserva consultarReserva(String id, String estudante) {
        return unidade.executar(() -> {
            Reserva reserva = movimentacao.reserva(id);
            return reserva != null && reserva.estudante().equals(estudante) ? reserva : null;
        });
    }

    public Comprovante emprestar(String id, String estudante, LocalDate vencimento) {
        return unidade.executar(() -> {
            ResultadoEmprestimo resultado = acervo.exigir(id).emprestar(movimentacao.reserva(id),
                    relacionamento.estudante(estudante), vencimento);
            movimentacao.consumirReserva(id);
            movimentacao.registrar(resultado.emprestimo());
            acervo.guardar(resultado.livro());
            return comprovante(resultado.emprestimo().id(), "empréstimo", resultado.livro(), resultado.emprestimo().vencimento());
        });
    }

    public Comprovante renovar(String id, String estudante) {
        return unidade.executar(() -> {
            Emprestimo atual = movimentacao.ativo(id);
            ResultadoRenovacao resultado = acervo.exigir(id).renovar(atual, estudante);
            movimentacao.registrar(resultado.emprestimo());
            acervo.guardar(resultado.livro());
            return comprovante(atual.id(), "renovação", resultado.livro(), resultado.emprestimo().vencimento());
        });
    }

    public Comprovante devolver(String id, String estudante, LocalDate data) {
        Devolucao evento = unidade.executar(() -> {
            Estudante cadastrado = relacionamento.estudante(estudante);
            Pessoa pessoa = cadastrado == null ? null : relacionamento.pessoa(cadastrado.cpf());
            ResultadoDevolucao resultado = acervo.exigir(id).devolver(movimentacao.ativo(id), cadastrado, pessoa, data);
            movimentacao.registrar(resultado.evento());
            acervo.guardar(resultado.livro());
            return resultado.evento();
        });
        publicador.publicar(evento);
        return new Comprovante(evento.emprestimo().id(), "devolução", "DEVOLVIDO", null);
    }

    public Comprovante inspecionar(String id, boolean danificado) {
        return alterar(id, "inspeção", livro -> livro.inspecionar(danificado));
    }
    Comprovante enviarParaReparo(String id) { return alterar(id, "reparo", Livro::enviarParaReparo); }
    Comprovante retornarDoReparo(String id) { return alterar(id, "retorno", Livro::retornarDoReparo); }
    Comprovante laudoIrrecuperavel(String id) {
        return alterar(id, "laudo irrecuperável", Livro::laudoIrrecuperavel);
    }

    private Comprovante alterar(String id, String operacao, UnaryOperator<Livro> transicao) {
        return unidade.executar(() -> {
            Livro livro = transicao.apply(acervo.exigir(id));
            acervo.guardar(livro);
            return comprovante(UUID.randomUUID(), operacao, livro, null);
        });
    }

    private Comprovante comprovante(UUID id, String operacao, Livro livro, LocalDate vencimento) {
        return new Comprovante(id, operacao, livro.estado().codigo(), vencimento);
    }
}

interface SolicitacaoCirculacao {
    UUID id();
    String autor();
    String livro();
    Instant criadoEm();
    Comprovante executar(iCirculacao receptor);
}

record SolicitarReserva(UUID id, String autor, Instant criadoEm, String livro)
        implements SolicitacaoCirculacao {
    public Comprovante executar(iCirculacao receptor) { return receptor.reservar(livro, autor); }
}
record SolicitarRenovacao(UUID id, String autor, Instant criadoEm, String livro)
        implements SolicitacaoCirculacao {
    public Comprovante executar(iCirculacao receptor) { return receptor.renovar(livro, autor); }
}
record RegistroSolicitacao(String tipo, String autor, String livro, Comprovante comprovante)
        implements Serializable {}

record ExecutorSolicitacoes(UnidadeDeTrabalho unidade, Repositorio<Serializable> repositorio,
                            iCirculacao receptor) {
    Comprovante executar(SolicitacaoCirculacao solicitacao) {
        return unidade.executar(() -> {
            String chave = "solicitacao:" + Objects.requireNonNull(solicitacao.id());
            RegistroSolicitacao anterior = (RegistroSolicitacao) repositorio.buscar(chave);
            String tipo = solicitacao.getClass().getSimpleName();
            String livro = solicitacao.livro();
            if (anterior != null) {
                if (!anterior.tipo().equals(tipo) || !anterior.autor().equals(solicitacao.autor())
                        || !anterior.livro().equals(livro)) {
                    throw new IllegalArgumentException("Identificador já usado por outra solicitação");
                }
                return anterior.comprovante();
            }
            Comprovante resultado = solicitacao.executar(receptor);
            repositorio.salvar(chave, new RegistroSolicitacao(tipo, solicitacao.autor(), livro, resultado));
            return resultado;
        });
    }
}

interface iEmprestimo {
    SituacaoLivro consultarSituacao(String livro);
    Comprovante solicitar(SolicitacaoCirculacao solicitacao);
    Reserva consultarReserva(String livro, String estudante);
    Comprovante emprestar(String livro, String estudante, LocalDate vencimento);
}
interface iDevolucao {
    Comprovante devolver(String livro, String estudante, LocalDate data);
    Comprovante inspecionar(String livro, boolean danificado);
}
record TratadorDeEmprestimo(iCirculacao fachada, ExecutorSolicitacoes executor) implements iEmprestimo {
    public SituacaoLivro consultarSituacao(String livro) { return fachada.consultarSituacao(livro); }
    public Comprovante solicitar(SolicitacaoCirculacao solicitacao) { return executor.executar(solicitacao); }
    public Reserva consultarReserva(String livro, String estudante) {
        return fachada.consultarReserva(livro, estudante);
    }
    public Comprovante emprestar(String livro, String estudante, LocalDate vencimento) {
        return fachada.emprestar(livro, estudante, vencimento);
    }
}
record TratadorDeDevolucao(FachadaCirculacao fachada, OuvinteInspecao inspecao) implements iDevolucao {
    public Comprovante devolver(String livro, String estudante, LocalDate data) {
        return fachada.devolver(livro, estudante, data);
    }
    public Comprovante inspecionar(String livro, boolean danificado) {
        Comprovante resultado = fachada.inspecionar(livro, danificado);
        inspecao.concluir(livro);
        return resultado;
    }
    List<String> pendentes() {
        return inspecao.fila();
    }
    Comprovante enviarParaReparo(String livro) { return fachada.enviarParaReparo(livro); }
    Comprovante retornarDoReparo(String livro) { return fachada.retornarDoReparo(livro); }
    Comprovante laudoIrrecuperavel(String livro) { return fachada.laudoIrrecuperavel(livro); }
}
