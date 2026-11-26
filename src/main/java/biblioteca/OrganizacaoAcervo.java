package biblioteca;

import java.io.Serializable;
import java.util.*;
import java.util.function.Function;

sealed interface NoAcervo permits GrupoAcervo, ItemAcervo {
    int contar();
    List<String> listar();
}
record EntradaAcervo(String nome, int nivel, boolean grupo, int quantidade) {}

record ItemAcervo(String idLivro) implements NoAcervo {
    ItemAcervo { Objects.requireNonNull(idLivro); }
    public int contar() { return 1; }
    public List<String> listar() { return List.of(idLivro); }
}

final class GrupoAcervo implements NoAcervo {
    private final String nome;
    private final List<NoAcervo> filhos = new ArrayList<>();
    private GrupoAcervo pai;

    GrupoAcervo(String nome) { this.nome = Objects.requireNonNull(nome); }
    String nome() { return nome; }
    GrupoAcervo pai() { return pai; }
    List<NoAcervo> filhos() { return List.copyOf(filhos); }
    void removerLivro(String id) {
        filhos.removeIf(no -> no instanceof ItemAcervo item && item.idLivro().equals(id));
        for (NoAcervo no : filhos) if (no instanceof GrupoAcervo grupo) grupo.removerLivro(id);
    }

    void adicionar(NoAcervo no) {
        Objects.requireNonNull(no);
        if (no instanceof GrupoAcervo grupo) {
            for (GrupoAcervo ancestral = this; ancestral != null; ancestral = ancestral.pai) {
                if (ancestral == grupo) throw new IllegalArgumentException("Ciclo no acervo");
            }
            if (grupo.pai != null) throw new IllegalArgumentException("Grupo já tem pai");
        }
        List<String> idsDaArvore = raiz().listar();
        if (no.listar().stream().anyMatch(idsDaArvore::contains)) {
            throw new IllegalArgumentException("Livro já pertence a um grupo");
        }
        filhos.add(no);
        if (no instanceof GrupoAcervo grupo) grupo.pai = this;
    }

    void mover(String id, GrupoAcervo destino) {
        if (raiz() != destino.raiz()) throw new IllegalArgumentException("Destino fora da unidade");
        ItemAcervo item = filhos.stream().filter(ItemAcervo.class::isInstance)
                .map(ItemAcervo.class::cast).filter(i -> i.idLivro().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Livro não pertence à seção"));
        filhos.remove(item);
        destino.filhos.add(item);
    }

    private GrupoAcervo raiz() {
        GrupoAcervo raiz = this;
        while (raiz.pai != null) raiz = raiz.pai;
        return raiz;
    }
    public int contar() { return filhos.stream().mapToInt(NoAcervo::contar).sum(); }
    public List<String> listar() { return filhos.stream().flatMap(no -> no.listar().stream()).toList(); }
    void detalhar(int nivel, List<EntradaAcervo> resultado) {
        resultado.add(new EntradaAcervo(nome, nivel, true, contar()));
        for (NoAcervo no : filhos) {
            if (no instanceof GrupoAcervo grupo) grupo.detalhar(nivel + 1, resultado);
            else resultado.add(new EntradaAcervo(((ItemAcervo) no).idLivro(), nivel + 1, false, 1));
        }
    }
}

// Registro plano: a profundidade da árvore não vira profundidade de serialização.
record RegistroGrupo(String id, String pai) implements Serializable {}
record RegistroItem(String grupo, String livro) implements Serializable {}
record RegistroOrganizacao(List<RegistroGrupo> grupos, List<RegistroItem> itens) implements Serializable {
    RegistroOrganizacao { grupos = List.copyOf(grupos); itens = List.copyOf(itens); }
}

final class ArvoreAcervo {
    final GrupoAcervo raiz = new GrupoAcervo("raiz");
    final Map<String, GrupoAcervo> grupos = new LinkedHashMap<>();
    ArvoreAcervo() { grupos.put("raiz", raiz); }
    GrupoAcervo grupo(String id) {
        GrupoAcervo grupo = grupos.get(id);
        if (grupo == null) throw new IllegalArgumentException("Grupo não localizado");
        return grupo;
    }
    void criarGrupo(String pai, String id) {
        RotinaCadastro.exigirTexto(id);
        if (grupos.containsKey(id)) throw new IllegalArgumentException("Grupo já cadastrado");
        GrupoAcervo novo = new GrupoAcervo(id);
        grupo(pai).adicionar(novo);
        grupos.put(id, novo);
    }
    RegistroOrganizacao registro() {
        List<RegistroGrupo> gs = new ArrayList<>();
        List<RegistroItem> itens = new ArrayList<>();
        for (GrupoAcervo grupo : grupos.values()) {
            if (grupo != raiz) gs.add(new RegistroGrupo(grupo.nome(), grupo.pai().nome()));
            for (NoAcervo no : grupo.filhos()) {
                if (no instanceof ItemAcervo item) itens.add(new RegistroItem(grupo.nome(), item.idLivro()));
            }
        }
        return new RegistroOrganizacao(gs, itens);
    }
}

final class OrganizacaoAcervo {
    private static final String CHAVE = "organizacao:arvore";
    private final Repositorio<Serializable> registros;
    OrganizacaoAcervo(Repositorio<Serializable> registros) { this.registros = registros; }
    ArvoreAcervo carregar() {
        ArvoreAcervo arvore = new ArvoreAcervo();
        RegistroOrganizacao registro = (RegistroOrganizacao) registros.buscar(CHAVE);
        if (registro != null) {
            for (RegistroGrupo grupo : registro.grupos()) arvore.criarGrupo(grupo.pai(), grupo.id());
            for (RegistroItem item : registro.itens()) arvore.grupo(item.grupo()).adicionar(new ItemAcervo(item.livro()));
        }
        return arvore;
    }
    void guardar(ArvoreAcervo arvore) { registros.salvar(CHAVE, arvore.registro()); }
    void removerLivro(String id) {
        ArvoreAcervo arvore = carregar();
        if (arvore.raiz.listar().contains(id)) {
            arvore.raiz.removerLivro(id);
            guardar(arvore);
        }
    }
}

final class TratadorDaOrganizacao {
    private final UnidadeDeTrabalho unidade;
    private final Acervo acervo;
    private final OrganizacaoAcervo organizacao;
    TratadorDaOrganizacao(UnidadeDeTrabalho unidade, Acervo acervo, OrganizacaoAcervo organizacao) {
        this.unidade = unidade;
        this.acervo = acervo;
        this.organizacao = organizacao;
    }
    void criarGrupo(String pai, String id) {
        alterar(arvore -> { arvore.criarGrupo(pai, id); return null; });
    }
    void incluirLivro(String grupo, String livro) {
        alterar(arvore -> {
            acervo.exigir(livro);
            arvore.grupo(grupo).adicionar(new ItemAcervo(livro));
            return null;
        });
    }
    void mover(String origem, String destino, String livro) {
        alterar(arvore -> { arvore.grupo(origem).mover(livro, arvore.grupo(destino)); return null; });
    }
    List<String> listar(String grupo) { return unidade.executar(() -> organizacao.carregar().grupo(grupo).listar()); }
    int contar(String grupo) { return unidade.executar(() -> organizacao.carregar().grupo(grupo).contar()); }
    List<EntradaAcervo> hierarquia() {
        return unidade.executar(() -> {
            List<EntradaAcervo> resultado = new ArrayList<>();
            organizacao.carregar().raiz.detalhar(0, resultado);
            return List.copyOf(resultado);
        });
    }
    private <T> T alterar(Function<ArvoreAcervo, T> acao) {
        return unidade.executar(() -> {
            ArvoreAcervo arvore = organizacao.carregar();
            T resultado = acao.apply(arvore);
            organizacao.guardar(arvore);
            return resultado;
        });
    }
}
