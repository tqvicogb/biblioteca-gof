package biblioteca;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

record Livro(String id, String titulo, Estado estado) {
    Livro(String id, String titulo) { this(id, titulo, new Livre()); }
    Livro com(Estado destino) { return new Livro(id, titulo, destino); }
    ResultadoReserva reservar(Estudante estudante) { return estado.reservar(this, estudante); }
    ResultadoEmprestimo emprestar(Reserva reserva, Estudante estudante, LocalDate vencimento) {
        return estado.emprestar(this, reserva, estudante, vencimento);
    }
    ResultadoDevolucao devolver(Emprestimo emprestimo, Estudante estudante, Pessoa pessoa, LocalDate data) {
        return estado.devolver(this, emprestimo, estudante, pessoa, data);
    }
    ResultadoRenovacao renovar(Emprestimo emprestimo, String estudante) {
        return estado.renovar(this, emprestimo, estudante);
    }
    ResultadoRenovacao renovar(Emprestimo emprestimo) {
        return renovar(emprestimo, emprestimo == null ? null : emprestimo.estudante());
    }
    Livro inspecionar(boolean danificado) { return estado.inspecionar(this, danificado); }
    Livro enviarParaReparo() { return estado.enviarParaReparo(this); }
    Livro retornarDoReparo() { return estado.retornarDoReparo(this); }
    Livro laudoIrrecuperavel() { return estado.laudoIrrecuperavel(this); }
    boolean aguardaInspecao() { return estado.aguardaInspecao(); }
    void excluir() { estado.excluir(); }
}

record ResultadoReserva(Livro livro, Reserva reserva) {}
record ResultadoEmprestimo(Livro livro, Emprestimo emprestimo) {}
record ResultadoRenovacao(Livro livro, Emprestimo emprestimo) {}
record ResultadoDevolucao(Livro livro, Devolucao evento) {}

interface Estado {
    default ResultadoReserva reservar(Livro l, Estudante estudante) { throw proibido(); }
    default ResultadoEmprestimo emprestar(Livro l, Reserva reserva, Estudante estudante, LocalDate prazo) { throw proibido(); }
    default ResultadoDevolucao devolver(Livro l, Emprestimo e, Estudante estudante, Pessoa pessoa, LocalDate data) { throw proibido(); }
    default ResultadoRenovacao renovar(Livro l, Emprestimo e, String estudante) { throw proibido(); }
    default Livro inspecionar(Livro l, boolean dano) { throw proibido(); }
    default Livro enviarParaReparo(Livro l) { throw proibido(); }
    default Livro retornarDoReparo(Livro l) { throw proibido(); }
    default Livro laudoIrrecuperavel(Livro l) { throw proibido(); }
    default boolean aguardaInspecao() { return false; }
    default void excluir() { throw proibido(); }

    default IllegalStateException proibido() {
        return new IllegalStateException("Operação indisponível no estado " + codigo());
    }
    default String codigo() { return getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT); }
    static Estado restaurar(String codigo) {
        return switch (codigo) {
            case "LIVRE" -> new Livre();
            case "RESERVADO" -> new Reservado();
            case "EMPRESTADO" -> new Emprestado();
            case "RENOVADO" -> new Renovado();
            case "DEVOLVIDO" -> new Devolvido();
            case "REPARANDO" -> new Reparando();
            case "INCINERADO" -> new Incinerado();
            default -> throw new IllegalArgumentException("Estado desconhecido: " + codigo);
        };
    }
    // Guardas comuns chamadas pelos métodos dos estados, sem acesso à persistência.
    static void exigirEstudante(Estudante estudante) {
        if (estudante == null) throw new IllegalArgumentException("Estudante não cadastrado");
    }
    static void exigirEmprestimo(Livro livro, Emprestimo e, String estudante) {
        if (e == null || !livro.id().equals(e.livro()) || !e.estudante().equals(estudante)) {
            throw new IllegalStateException("Empréstimo ativo do estudante não encontrado");
        }
    }
    static ResultadoDevolucao concluirDevolucao(Livro l, Emprestimo e, Estudante estudante, Pessoa pessoa, LocalDate data) {
        exigirEstudante(estudante);
        exigirEmprestimo(l, e, estudante.id());
        if (pessoa == null || !estudante.cpf().equals(pessoa.cpf())) {
            throw new IllegalArgumentException("Pessoa do estudante não encontrada");
        }
        Objects.requireNonNull(data, "Data da devolução obrigatória");
        return new ResultadoDevolucao(l.com(new Devolvido()), new Devolucao(e, estudante, pessoa, data));
    }
}

final class Livre implements Estado {
    public ResultadoReserva reservar(Livro l, Estudante estudante) {
        Estado.exigirEstudante(estudante);
        Reserva reserva = new Reserva(UUID.randomUUID(), l.id(), estudante.id());
        return new ResultadoReserva(l.com(new Reservado()), reserva);
    }
    public Livro enviarParaReparo(Livro l) { return l.com(new Reparando()); }
    public void excluir() { }
}
final class Reservado implements Estado {
    public ResultadoEmprestimo emprestar(Livro l, Reserva reserva, Estudante estudante, LocalDate prazo) {
        Estado.exigirEstudante(estudante);
        if (reserva == null || !l.id().equals(reserva.livro()) || !estudante.id().equals(reserva.estudante())) {
            throw new IllegalStateException("Reserva válida do estudante é obrigatória");
        }
        Objects.requireNonNull(prazo, "Vencimento obrigatório");
        Emprestimo emprestimo = new Emprestimo(UUID.randomUUID(), l.id(), estudante.id(), prazo, 0);
        return new ResultadoEmprestimo(l.com(new Emprestado()), emprestimo);
    }
}
final class Emprestado implements Estado {
    public ResultadoDevolucao devolver(Livro l, Emprestimo e, Estudante estudante, Pessoa pessoa, LocalDate data) {
        return Estado.concluirDevolucao(l, e, estudante, pessoa, data);
    }
    public ResultadoRenovacao renovar(Livro l, Emprestimo e, String estudante) {
        Estado.exigirEmprestimo(l, e, estudante);
        if (e.renovacoes() != 0) throw proibido();
        return new ResultadoRenovacao(l.com(new Renovado()), e.renovado());
    }
}
final class Renovado implements Estado {
    public ResultadoDevolucao devolver(Livro l, Emprestimo e, Estudante estudante, Pessoa pessoa, LocalDate data) {
        return Estado.concluirDevolucao(l, e, estudante, pessoa, data);
    }
    public ResultadoRenovacao renovar(Livro l, Emprestimo e, String estudante) {
        Estado.exigirEmprestimo(l, e, estudante);
        if (e.renovacoes() >= 2) throw new IllegalStateException("Limite de duas renovações atingido. Vencimento preservado.");
        return new ResultadoRenovacao(l, e.renovado());
    }
}
final class Devolvido implements Estado {
    public boolean aguardaInspecao() { return true; }
    public Livro inspecionar(Livro l, boolean dano) { return l.com(dano ? new Reparando() : new Livre()); }
}
final class Reparando implements Estado {
    public Livro retornarDoReparo(Livro l) { return l.com(new Livre()); }
    public Livro laudoIrrecuperavel(Livro l) { return l.com(new Incinerado()); }
}
final class Incinerado implements Estado { }
