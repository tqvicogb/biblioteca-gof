const areas = {
  "Circulação": [
    ["reservar", "Solicitar reserva", "livro estudante uuid"],
    ["reserva-atual", "Consultar reserva", "livro estudante"],
    ["emprestar", "Efetivar empréstimo", "livro estudante vencimento"],
    ["renovar", "Renovar empréstimo", "livro estudante uuid"],
    ["devolver", "Registrar devolução", "livro estudante devolucao"],
    ["inspecionar", "Registrar inspeção", "livro laudo"],
    ["reparar", "Enviar para reparo", "livro"],
    ["retornar", "Registrar retorno do reparo", "livro"],
    ["laudo", "Registrar laudo irrecuperável", "livro"]
  ],
  "Cadastros": [
    ["pessoa", "Cadastrar pessoa", "cpf nome email"],
    ["estudante", "Cadastrar estudante", "estudante cpf"],
    ["atendente", "Cadastrar atendente", "atendente cpf"],
    ["livro", "Cadastrar livro", "livro titulo"]
  ],
  "Acervo": [
    ["consulta", "Consultar livro", "livro"],
    ["grupo", "Criar grupo", "origem destino"],
    ["organizar", "Incluir livro no grupo", "origem livro"],
    ["mover", "Mover livro entre grupos", "origem destino livro"],
    ["listar", "Listar livros de um grupo", "origem"]
  ],
  "Financeiro": [["multa", "Consultar multa", "emprestimo"]]
};
const campos = {
  livro: ["ID do livro"], estudante: ["ID do estudante"], atendente: ["ID do atendente"],
  titulo: ["Título"], cpf: ["CPF"], nome: ["Nome"], email: ["E-mail", "email"],
  uuid: ["Identificador da solicitação"], emprestimo: ["Comprovante do empréstimo"],
  vencimento: ["Vencimento do empréstimo", "date"], devolucao: ["Data de devolução", "date"],
  origem: ["Grupo de origem ou grupo pai"], destino: ["Grupo de destino ou novo grupo"]
};

const form = document.querySelector("#operacao");
const select = document.querySelector("#acao");
const status = document.querySelector("#status");
const historico = document.querySelector("#historico");
const demo = document.querySelector("#demonstrar");
const enviar = document.querySelector("#enviar");
const valores = new Map();
let operacoes;
let emprestimo = "";

function selecionar(area) {
  operacoes = areas[area];
  document.querySelector("#form-titulo").textContent = area;
  document.querySelectorAll("nav button").forEach(botao =>
    botao.setAttribute("aria-pressed", String(botao.textContent === area)));
  select.replaceChildren(...operacoes.map(([id, titulo]) => new Option(titulo, id)));
  montarCampos();
}

function montarCampos() {
  const [, , chaves] = operacoes.find(([id]) => id === select.value);
  const corpo = document.querySelector("#campos");
  corpo.replaceChildren();
  for (const chave of chaves.split(" ")) {
    const label = document.createElement("label");
    let input;
    if (chave === "laudo") {
      label.textContent = "Condição do exemplar";
      input = document.createElement("select");
      input.append(new Option("Em bom estado", "bom"), new Option("Danificado", "danificado"));
    } else {
      const [titulo, tipo] = campos[chave];
      label.textContent = titulo;
      input = document.createElement("input");
      input.type = tipo || "text";
      input.maxLength = 180;
      input.value = chave === "uuid" ? crypto.randomUUID()
        : chave === "emprestimo" ? emprestimo
        : valores.get(chave) || (chave === "origem" ? "raiz" : "");
    }
    input.name = {vencimento: "data", devolucao: "data", emprestimo: "uuid"}[chave] || chave;
    input.required = true;
    input.addEventListener("input", () => { if (chave !== "uuid") valores.set(chave, input.value); });
    label.append(input);
    corpo.append(label);
    if (chave === "uuid") {
      const novo = document.createElement("button");
      novo.type = "button";
      novo.className = "secondary";
      novo.textContent = "Gerar nova solicitação";
      novo.addEventListener("click", () => { input.value = crypto.randomUUID(); input.focus(); });
      corpo.append(novo);
    }
  }
}

async function executar(titulo, dados) {
  let texto;
  let ok = false;
  try {
    const resposta = await fetch("/operacao", {
      method: "POST",
      headers: {"X-Biblioteca": "1"},
      body: new URLSearchParams(dados)
    });
    texto = await resposta.text();
    ok = resposta.ok;
  } catch {
    texto = "Servidor indisponível. Confira se a aplicação está aberta.";
  }
  const item = document.createElement("li");
  if (!ok) item.className = "falha";
  const nome = document.createElement("strong");
  const resultado = document.createElement("p");
  nome.textContent = titulo;
  resultado.textContent = texto;
  item.append(nome, resultado);
  historico.prepend(item);
  status.textContent = ok ? "Operação concluída." : "Operação recusada. Confira o resultado.";
  if (ok && dados.acao === "emprestar") emprestimo = texto.match(/Comprovante: (\S+)/)?.[1] || "";
  return ok;
}

async function ocupado(acao) {
  demo.disabled = enviar.disabled = true;
  try { await acao(); }
  finally { demo.disabled = enviar.disabled = false; }
}

form.addEventListener("submit", evento => {
  evento.preventDefault();
  const dados = Object.fromEntries(new FormData(form));
  const [, titulo] = operacoes.find(([id]) => id === dados.acao);
  ocupado(() => executar(titulo, dados));
});
select.addEventListener("change", montarCampos);
document.querySelector("#limpar").addEventListener("click", () => {
  historico.replaceChildren();
  status.textContent = "Os comprovantes e retornos aparecem aqui.";
});
for (const area of Object.keys(areas)) {
  const botao = document.createElement("button");
  botao.type = "button";
  botao.textContent = area;
  botao.addEventListener("click", () => selecionar(area));
  document.querySelector("#areas").append(botao);
}
selecionar("Circulação");

demo.addEventListener("click", () => ocupado(async () => {
  const cpf = "12345678909";
  const estudante = "DEMO";
  const livro = "L-" + crypto.randomUUID().slice(0, 8);
  const passos = [
    ["Pessoa", {acao: "pessoa", cpf, nome: "Estudante de demonstração", email: "demo@example.org"}],
    ["Estudante", {acao: "estudante", estudante, cpf}],
    ["Livro", {acao: "livro", livro, titulo: "Modelagem de Sistemas"}],
    ["Reserva", {acao: "reservar", livro, estudante, uuid: crypto.randomUUID()}],
    ["Empréstimo", {acao: "emprestar", livro, estudante, data: "2026-10-18"}],
    ["Renovação", {acao: "renovar", livro, estudante, uuid: crypto.randomUUID()}],
    ["Devolução", {acao: "devolver", livro, estudante, data: "2026-11-22"}]
  ];
  for (const [titulo, dados] of passos) {
    if (!await executar(titulo, dados)) return;
  }
  valores.set("livro", livro);
  valores.set("estudante", estudante);
  await executar("Multa", {acao: "multa", uuid: emprestimo});
}));