# PISA (Portal to ISAbelle)
PISA supports automated proof search with the interactive theorem prover 
[Isabelle](https://isabelle.in.tum.de).

See [this](https://terminalizer.com/view/cb6ea5dd5395) for how to write proofs with a Python script 
with PISA.

PISA can also be used to extract proof corpus. 
We extracted the datasets in our AITP 2021 paper 
[LISA: Language models of ISAbelle proofs](http://aitp-conference.org/2021/abstract/paper_17.pdf) 
with it.

- [Installation](install)
- [Extraction](extract)
- [Documentation of the PISA protocol](protocol)

# To be released
- REPL-like environment to talk to Isabelle running sessions via gRPC
- Setup to evaluate automated agents

# Acknowledgement
This library is heavily based on [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle), the work of Dominique Unruh. We are very grateful to Dominique for his kind help and guidance.

# Citation
If you use this directory, or our code, please cite the paper we had in AITP 2021.
```bibtex
@article{jiang2021lisa,
  title={LISA: Language models of ISAbelle proofs},
  author={Jiang, Albert Qiaochu and Li, Wenda and Han, Jesse Michael and Wu, Yuhuai},
  year={2021},
  journal={6th Conference on Artificial Intelligence and Theorem Proving},
}
```
