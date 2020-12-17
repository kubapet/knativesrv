#!/bin/bash

if [[ -z $(kubectl get ingress ingress -o yaml | grep "serviceName: jakubsnative-service") ]]
then echo "$(kubectl get ingress ingress -o yaml | sed '/^status:$/Q')
  - host: jakubsnative.dossiercloud.gq
    http:
      paths:
      - path: "/*"
        backend:
          serviceName: jakubsnative-service
          servicePort: 80
$(kubectl get ingress ingress -o yaml | sed -n -e '/^status:$/,$p')" | kubectl apply -f -
fi
