package biblioteca;

import java.util.Locale;

record ResultadoCadastro<T>(T cadastro, boolean existente) {}
interface iLivro { ResultadoCadastro<Livro> incluir(Livro livro); }
interface iPessoa { ResultadoCadastro<Pessoa> incluir(Pessoa pessoa); }
interface iEstudante { ResultadoCadastro<Estudante> incluir(Estudante estudante); }
interface iAtendente { ResultadoCadastro<Atendente> incluir(Atendente atendente); }

abstract class RotinaCadastro<T> {
    protected final UnidadeDeTrabalho unidade;
    RotinaCadastro(UnidadeDeTrabalho unidade) { this.unidade = unidade; }

    public final ResultadoCadastro<T> incluir(T entrada) {
        return unidade.executar(() -> {
            T existente = buscar(entrada);
            if (existente != null) return apresentar(existente, true);
            validar(entrada);
            verificarPreRequisitos(entrada);
            T criado = criar(entrada);
            armazenar(criado);
            return apresentar(criado, false);
        });
    }
    abstract T buscar(T entrada);
    abstract void validar(T entrada);
    void verificarPreRequisitos(T entrada) {}
    abstract T criar(T entrada);
    abstract void armazenar(T cadastro);
    ResultadoCadastro<T> apresentar(T cadastro, boolean existente) {
        return new ResultadoCadastro<>(cadastro, existente);
    }

    static void exigirTexto(String texto) {
        if (texto == null || texto.isBlank() || texto.length() > 180) {
            throw new IllegalArgumentException("Campo obrigatório ou acima do limite");
        }
    }
    static String cpf(String valor) {
        exigirTexto(valor);
        String normalizado = valor.replaceAll("[.\\- ]", "");
        if (!normalizado.matches("[0-9]{11}")) throw new IllegalArgumentException("CPF inválido");
        return normalizado;
    }
}

final class TratadorDeAcervo extends RotinaCadastro<Livro> implements iLivro {
    private final Acervo acervo;
    private final UnidadeDeTrabalho unidade;
    private final ConsultaAcervo consulta;
    private final Movimentacao movimentacao;
    private final OrganizacaoAcervo organizacao;
    TratadorDeAcervo(UnidadeDeTrabalho unidade, Acervo acervo, ConsultaAcervo consulta,
                     Movimentacao movimentacao, OrganizacaoAcervo organizacao) {
        super(unidade);
        this.unidade = unidade;
        this.acervo = acervo;
        this.consulta = consulta;
        this.movimentacao = movimentacao;
        this.organizacao = organizacao;
    }
    Livro buscar(Livro l) { return acervo.buscar(l.id()); }
    void validar(Livro l) { exigirTexto(l.id()); exigirTexto(l.titulo()); }
    Livro criar(Livro l) { return new Livro(l.id(), l.titulo().strip()); }
    void armazenar(Livro l) {
        acervo.guardar(l);
        consulta.invalidar(l.id());
    }
    Metadados consultar(String id) { return consulta.consultar(id); }
    void excluir(String id) {
        unidade.executar(() -> {
            acervo.excluir(id);
            organizacao.removerLivro(id);
            movimentacao.desvincularLivroExcluido(id);
            consulta.invalidar(id);
            return null;
        });
    }
}

final class TratadorDePessoa extends RotinaCadastro<Pessoa> implements iPessoa {
    private final Relacionamento relacionamento;
    TratadorDePessoa(UnidadeDeTrabalho unidade, Relacionamento relacionamento) {
        super(unidade);
        this.relacionamento = relacionamento;
    }
    Pessoa buscar(Pessoa p) { return relacionamento.pessoa(cpf(p.cpf())); }
    void validar(Pessoa p) {
        exigirTexto(p.nome());
        exigirTexto(p.email());
        if (!p.email().contains("@")) throw new IllegalArgumentException("E-mail inválido");
    }
    Pessoa criar(Pessoa p) {
        return new Pessoa(cpf(p.cpf()), p.nome().strip(), p.email().strip().toLowerCase(Locale.ROOT));
    }
    void armazenar(Pessoa p) { relacionamento.guardar(p); }
    Pessoa consultar(String documento) {
        return unidade.executar(() -> relacionamento.pessoa(cpf(documento)));
    }
}

final class TratadorDeEstudante extends RotinaCadastro<Estudante> implements iEstudante {
    private final Relacionamento relacionamento;
    TratadorDeEstudante(UnidadeDeTrabalho unidade, Relacionamento relacionamento) {
        super(unidade);
        this.relacionamento = relacionamento;
    }
    Estudante buscar(Estudante e) { return relacionamento.estudante(e.id()); }
    void validar(Estudante e) { exigirTexto(e.id()); cpf(e.cpf()); }
    void verificarPreRequisitos(Estudante e) {
        if (relacionamento.pessoa(cpf(e.cpf())) == null) throw new IllegalArgumentException("CPF sem cadastro");
    }
    Estudante criar(Estudante e) { return new Estudante(e.id(), cpf(e.cpf())); }
    void armazenar(Estudante e) { relacionamento.guardar(e); }
    Estudante consultar(String id) { return unidade.executar(() -> relacionamento.estudante(id)); }
}

final class TratadorDeAtendente extends RotinaCadastro<Atendente> implements iAtendente {
    private final Relacionamento relacionamento;
    TratadorDeAtendente(UnidadeDeTrabalho unidade, Relacionamento relacionamento) {
        super(unidade);
        this.relacionamento = relacionamento;
    }
    Atendente buscar(Atendente a) { return relacionamento.atendente(a.id()); }
    void validar(Atendente a) { exigirTexto(a.id()); cpf(a.cpf()); }
    void verificarPreRequisitos(Atendente a) {
        if (relacionamento.pessoa(cpf(a.cpf())) == null) throw new IllegalArgumentException("CPF sem cadastro");
    }
    Atendente criar(Atendente a) { return new Atendente(a.id(), cpf(a.cpf())); }
    void armazenar(Atendente a) { relacionamento.guardar(a); }
    Atendente consultar(String id) { return unidade.executar(() -> relacionamento.atendente(id)); }
}
