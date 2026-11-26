package biblioteca;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

interface UnidadeDeTrabalho {
    <T> T executar(Supplier<T> acao);
}

interface Repositorio<T> {
    T buscar(String id);
    default List<T> listar() { throw new UnsupportedOperationException("Listagem não implementada"); }
    void salvar(String id, T valor);
    void remover(String id);
}

interface FabricaPersistencia {
    UnidadeDeTrabalho unidade();
    Repositorio<RegistroLivro> livros();
    Repositorio<Serializable> movimentacoes();
    Repositorio<Serializable> relacionamentos();
}

record RegistroLivro(String id, String titulo, String estado) implements Serializable {
    Livro livro() { return new Livro(id, titulo, Estado.restaurar(estado)); }
}
final class FabricaEmMemoria implements FabricaPersistencia {
    private final UnidadeEmMemoria unidade = new UnidadeEmMemoria();
    private final Repositorio<RegistroLivro> livros = unidade.criar();
    private final Repositorio<Serializable> movimentos = unidade.criar();
    private final Repositorio<Serializable> pessoas = unidade.criar();
    public UnidadeEmMemoria unidade() { return unidade; }
    public Repositorio<RegistroLivro> livros() { return livros; }
    public Repositorio<Serializable> movimentacoes() { return movimentos; }
    public Repositorio<Serializable> relacionamentos() { return pessoas; }
}

final class UnidadeEmMemoria implements UnidadeDeTrabalho {
    private Map<Integer, Map<String, Object>> dados = new HashMap<>();
    private int proximaColecao;

    synchronized <T> RepositorioEmMemoria<T> criar() {
        int numero = proximaColecao++;
        dados.put(numero, new HashMap<>());
        return new RepositorioEmMemoria<>(this, numero);
    }

    Map<String, Object> dados(int numero) { return dados.get(numero); }

    public synchronized <T> T executar(Supplier<T> acao) {
        Map<Integer, Map<String, Object>> anterior = new HashMap<>();
        dados.forEach((numero, valores) -> anterior.put(numero, new HashMap<>(valores)));
        try { return acao.get(); }
        catch (RuntimeException | Error erro) { dados = anterior; throw erro; }
    }
}

record RepositorioEmMemoria<T>(UnidadeEmMemoria unidade, int numero) implements Repositorio<T> {
    @SuppressWarnings("unchecked")
    public T buscar(String id) { return (T) unidade.dados(numero).get(id); }
    @SuppressWarnings("unchecked")
    public List<T> listar() { return unidade.dados(numero).values().stream().map(v -> (T) v).toList(); }
    public void salvar(String id, T valor) { unidade.dados(numero).put(id, valor); }
    public void remover(String id) { unidade.dados(numero).remove(id); }
}

final class FabricaMySQL implements FabricaPersistencia {
    private final UnidadeMySQL unidade;
    private final Repositorio<RegistroLivro> livros;
    private final Repositorio<Serializable> movimentos;
    private final Repositorio<Serializable> pessoas;
    FabricaMySQL(String url, String usuario, String senha) {
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("Informe BIB_JDBC com URL do MySQL principal");
        }
        unidade = new UnidadeMySQL(url, usuario, senha);
        livros = new RepositorioMySQL<>(unidade, "livro");
        movimentos = new RepositorioMySQL<>(unidade, "movimentacao");
        pessoas = new RepositorioMySQL<>(unidade, "relacionamento");
    }
    public UnidadeMySQL unidade() { return unidade; }
    public Repositorio<RegistroLivro> livros() { return livros; }
    public Repositorio<Serializable> movimentacoes() { return movimentos; }
    public Repositorio<Serializable> relacionamentos() { return pessoas; }
}

final class UnidadeMySQL implements UnidadeDeTrabalho {
    private final String url;
    private final String usuario;
    private final String senha;
    private final ThreadLocal<Connection> atual = new ThreadLocal<>();

    UnidadeMySQL(String url, String usuario, String senha) {
        this.url = url;
        this.usuario = usuario;
        this.senha = senha;
    }

    Connection conexao() {
        if (atual.get() == null) throw new IllegalStateException("Transação obrigatória");
        return atual.get();
    }

    public <T> T executar(Supplier<T> acao) {
        if (atual.get() != null) return acao.get();
        try (Connection conexao = DriverManager.getConnection(url, usuario, senha)) {
            conexao.setAutoCommit(false);
            atual.set(conexao);
            try {
                // Serializa operações de circulação e cadastros entre processos.
                try (var stmt = conexao.prepareStatement("SELECT id FROM unidade_lock WHERE id = 1 FOR UPDATE");
                     var linhas = stmt.executeQuery()) {
                    if (!linhas.next()) throw new IllegalStateException("Execute schema.sql");
                }
                T resultado = acao.get();
                conexao.commit();
                return resultado;
            } catch (RuntimeException | Error | SQLException erro) {
                try { conexao.rollback(); }
                catch (SQLException rollback) { erro.addSuppressed(rollback); }
                throw erro;
            } finally {
                atual.remove();
            }
        } catch (SQLException erro) {
            throw new IllegalStateException("Falha na transação MySQL", erro);
        }
    }
}

record RepositorioMySQL<T extends Serializable>(UnidadeMySQL unidade, String familia) implements Repositorio<T> {
    @SuppressWarnings("unchecked")
    public T buscar(String id) {
        try (var stmt = unidade.conexao().prepareStatement(
                "SELECT conteudo FROM biblioteca_dados WHERE familia = ? AND id = ?")) {
            stmt.setString(1, familia);
            stmt.setString(2, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                try (var entrada = new ObjectInputStream(new ByteArrayInputStream(rs.getBytes(1)))) {
                    entrada.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                            "maxdepth=12;maxbytes=1048576;biblioteca.*;java.base/*;!*"));
                    return (T) entrada.readObject();
                }
            }
        } catch (SQLException | IOException | ClassNotFoundException erro) {
            throw new IllegalStateException("Falha ao ler " + familia, erro);
        }
    }

    public List<T> listar() {
        List<String> ids = new ArrayList<>();
        try (var stmt = unidade.conexao().prepareStatement(
                "SELECT id FROM biblioteca_dados WHERE familia = ? ORDER BY id")) {
            stmt.setString(1, familia);
            try (var rs = stmt.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
        } catch (SQLException erro) { throw new IllegalStateException("Falha ao listar " + familia, erro); }
        List<T> registros = new ArrayList<>();
        for (String id : ids) registros.add(buscar(id));
        return List.copyOf(registros);
    }

    public void salvar(String id, T valor) {
        try (var buffer = new ByteArrayOutputStream();
             var saida = new ObjectOutputStream(buffer);
             var stmt = unidade.conexao().prepareStatement(
                 "INSERT INTO biblioteca_dados VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE conteudo = ?")) {
            saida.writeObject(valor);
            stmt.setString(1, familia);
            stmt.setString(2, id);
            stmt.setBytes(3, buffer.toByteArray());
            stmt.setBytes(4, buffer.toByteArray());
            stmt.executeUpdate();
        } catch (SQLException | IOException erro) {
            throw new IllegalStateException("Falha ao gravar " + familia, erro);
        }
    }

    public void remover(String id) {
        try (var stmt = unidade.conexao().prepareStatement(
                "DELETE FROM biblioteca_dados WHERE familia = ? AND id = ?")) {
            stmt.setString(1, familia);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException erro) {
            throw new IllegalStateException("Falha ao remover " + familia, erro);
        }
    }
}